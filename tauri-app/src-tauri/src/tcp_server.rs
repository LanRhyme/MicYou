use micyou_protocol::micyou::MessageWrapper;
use micyou_protocol::{HANDSHAKE_CLIENT_STR, HANDSHAKE_SERVER_STR, PACKET_MAGIC};
use prost::Message;
use serde::Serialize;
use std::error::Error;
use std::io::{Error as IoError, ErrorKind};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tokio::task::{JoinHandle, JoinSet};
use tokio::time::timeout;
use tokio_util::sync::CancellationToken;

use crate::audio_stream::{validate_audio_packet, AudioStreamEvent, ExpectedAudioSession};
use crate::udp_server::{
    try_accept_audio_packet, ActiveAudioSession, AudioPacketAcceptance, SharedActiveAudioSession,
};

const FRAME_HEADER_LEN: usize = 8;
// Audio protobuf frames are normally only a few KiB. One MiB leaves ample codec and
// control-message headroom while bounding allocations from an untrusted peer.
const MAX_CONTROL_PAYLOAD_LEN: usize = 1024 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_CONCURRENT_CLIENTS: usize = 64;
const FRAME_READ_TIMEOUT: Duration = Duration::from_secs(10);
const FRAME_WRITE_TIMEOUT: Duration = Duration::from_secs(10);
const CLIENT_SHUTDOWN_GRACE_PERIOD: Duration = Duration::from_millis(250);
static NEXT_CONNECTION_ID: AtomicU64 = AtomicU64::new(1);

#[cfg(windows)]
pub type RawSocketHandle = std::os::windows::io::RawSocket;
#[cfg(unix)]
pub type RawSocketHandle = std::os::unix::io::RawFd;

pub struct ActiveConnection {
    pub sender: tokio::sync::mpsc::Sender<MessageWrapper>,
    pub raw_socket: RawSocketHandle,
    pub connection_id: u64,
    pub takeover_token: CancellationToken,
}

pub type SharedActiveConnection = Arc<Mutex<Option<ActiveConnection>>>;
pub type SharedTakeoverLock = Arc<Mutex<()>>;

struct TaskGuard {
    tasks: Vec<JoinHandle<()>>,
}

impl TaskGuard {
    fn new(tasks: Vec<JoinHandle<()>>) -> Self {
        Self { tasks }
    }

    async fn abort_and_wait(mut self) {
        for task in &self.tasks {
            task.abort();
        }
        for task in self.tasks.drain(..) {
            let _ = task.await;
        }
    }
}

impl Drop for TaskGuard {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

#[derive(Serialize, Clone)]
pub struct DeviceInfo {
    pub name: String,
    pub ip: String,
    pub latency: u32,
}

pub async fn start_tcp_server(
    app_handle: AppHandle,
    port: u16,
    bind_address: String,
    cancel_token: CancellationToken,
    audio_tx: tokio::sync::mpsc::Sender<AudioStreamEvent>,
    stats: Arc<crate::stats::NetworkStats>,
    mode: String,
    active_connection: SharedActiveConnection,
    takeover_lock: SharedTakeoverLock,
    active_audio_session: SharedActiveAudioSession,
    ready: tokio::sync::oneshot::Sender<Result<(), String>>,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let listener = match TcpListener::bind(format!("{}:{}", bind_address, port)).await {
        Ok(listener) => listener,
        Err(error) => {
            let _ = ready.send(Err(error.to_string()));
            return Err(Box::new(error));
        }
    };
    let _ = ready.send(Ok(()));
    println!("TCP Control Server listening on {}:{}", bind_address, port);

    let mut clients = JoinSet::new();
    let client_slots = Arc::new(tokio::sync::Semaphore::new(MAX_CONCURRENT_CLIENTS));
    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => {
                println!("TCP Server cancelled");
                break;
            }
            Some(result) = clients.join_next(), if !clients.is_empty() => {
                if let Err(e) = result {
                    eprintln!("TCP client task failed: {}", e);
                }
            }
            accept_result = listener.accept() => {
                match accept_result {
                    Ok((socket, addr)) => {
                        let permit = match client_slots.clone().try_acquire_owned() {
                            Ok(permit) => permit,
                            Err(_) => {
                                log::warn!("TCP client limit reached; rejecting {}", addr);
                                continue;
                            }
                        };
                        println!("New client connected: {}", addr);
                        let app_handle = app_handle.clone();
                        let audio_tx = audio_tx.clone();
                        let stats = stats.clone();
                        let mode = mode.clone();
                        let active_connection = active_connection.clone();
                        let takeover_lock = takeover_lock.clone();
                        let active_audio_session = active_audio_session.clone();
                        let client_cancel = cancel_token.clone();
                        clients.spawn(async move {
                            let _permit = permit;
                            if let Err(e) = handle_client(
                                socket,
                                addr,
                                app_handle,
                                audio_tx,
                                stats,
                                mode,
                                active_connection,
                                takeover_lock,
                                active_audio_session,
                                client_cancel,
                            ).await {
                                eprintln!("Client {} error: {}", addr, e);
                            }
                            println!("Client {} disconnected", addr);
                        });
                    }
                    Err(e) => eprintln!("Failed to accept TCP connection: {}", e),
                }
            }
        }
    }

    // Take ownership of the published raw socket while handle_client still owns the
    // corresponding TcpStream, then shut it down so client tasks can exit cooperatively.
    cleanup_session_state(&active_connection, &active_audio_session).await;
    if timeout(CLIENT_SHUTDOWN_GRACE_PERIOD, async {
        while clients.join_next().await.is_some() {}
    })
    .await
    .is_err()
    {
        clients.abort_all();
        while clients.join_next().await.is_some() {}
    }
    Ok(())
}

pub async fn cleanup_session_state(
    active_connection: &SharedActiveConnection,
    active_audio_session: &SharedActiveAudioSession,
) {
    cleanup_session_state_with(
        active_connection,
        active_audio_session,
        force_close_socket,
    )
    .await;
}

async fn cleanup_session_state_with<F>(
    active_connection: &SharedActiveConnection,
    active_audio_session: &SharedActiveAudioSession,
    mut shutdown_socket: F,
) where
    F: FnMut(RawSocketHandle),
{
    // Remove the handle from shared state before cancellation or shutdown. Later cleanup
    // callers must not be able to act on a descriptor that its TcpStream may have closed.
    let connection = {
        let mut active = active_connection.lock().await;
        active.take()
    };
    if let Some(connection) = connection {
        connection.takeover_token.cancel();
        shutdown_socket(connection.raw_socket);
        drop(connection.sender);
    }
    match active_audio_session.write() {
        Ok(mut active) => *active = ActiveAudioSession::default(),
        Err(poisoned) => *poisoned.into_inner() = ActiveAudioSession::default(),
    }
}

#[cfg(windows)]
fn safe_shutdown_socket(raw: RawSocketHandle) -> std::io::Result<()> {
    let status = unsafe {
        winapi::um::winsock2::shutdown(
            raw as winapi::um::winsock2::SOCKET,
            winapi::um::winsock2::SD_BOTH,
        )
    };
    if status == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

#[cfg(unix)]
fn safe_shutdown_socket(raw: RawSocketHandle) -> std::io::Result<()> {
    let status = unsafe { libc::shutdown(raw, libc::SHUT_RDWR) };
    if status == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

pub fn force_close_socket(raw: RawSocketHandle) {
    if let Err(e) = safe_shutdown_socket(raw) {
        eprintln!("Force close socket error: {}", e);
    }
}

fn parse_frame_header(header: &[u8; FRAME_HEADER_LEN]) -> Result<usize, IoError> {
    let magic = i32::from_be_bytes(header[0..4].try_into().unwrap());
    if magic != PACKET_MAGIC {
        return Err(IoError::new(ErrorKind::InvalidData, "invalid packet magic"));
    }

    let payload_len_i32 = i32::from_be_bytes(header[4..8].try_into().unwrap());
    if payload_len_i32 < 0 {
        return Err(IoError::new(
            ErrorKind::InvalidData,
            "negative payload length",
        ));
    }
    let payload_len = usize::try_from(payload_len_i32)
        .map_err(|_| IoError::new(ErrorKind::InvalidData, "invalid payload length"))?;
    if payload_len > MAX_CONTROL_PAYLOAD_LEN {
        return Err(IoError::new(
            ErrorKind::InvalidData,
            "payload length exceeds limit",
        ));
    }
    FRAME_HEADER_LEN
        .checked_add(payload_len)
        .ok_or_else(|| IoError::new(ErrorKind::InvalidData, "frame length overflow"))?;
    Ok(payload_len)
}

async fn run_if_active<F>(
    active: &SharedActiveConnection,
    takeover_token: &CancellationToken,
    connection_id: u64,
    action: F,
) -> bool
where
    F: FnOnce(),
{
    if takeover_token.is_cancelled() {
        return false;
    }
    let active = active.lock().await;
    if takeover_token.is_cancelled()
        || !active
            .as_ref()
            .is_some_and(|connection| connection.connection_id == connection_id)
    {
        return false;
    }
    action();
    true
}

async fn clear_if_active(active: &SharedActiveConnection, connection_id: u64) -> bool {
    let mut lock = active.lock().await;
    if lock
        .as_ref()
        .is_some_and(|connection| connection.connection_id == connection_id)
    {
        *lock = None;
        true
    } else {
        false
    }
}

async fn handle_client(
    mut socket: TcpStream,
    addr: SocketAddr,
    app_handle: AppHandle,
    audio_tx: tokio::sync::mpsc::Sender<AudioStreamEvent>,
    stats: Arc<crate::stats::NetworkStats>,
    mode: String,
    active_connection: SharedActiveConnection,
    takeover_lock: SharedTakeoverLock,
    active_audio_session: SharedActiveAudioSession,
    cancel_token: CancellationToken,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let mut handshake_buf = vec![0u8; HANDSHAKE_CLIENT_STR.len()];
    tokio::select! {
        _ = cancel_token.cancelled() => return Ok(()),
        result = timeout(HANDSHAKE_TIMEOUT, socket.read_exact(&mut handshake_buf)) => {
            result.map_err(|_| IoError::new(ErrorKind::TimedOut, "handshake read timed out"))??;
        }
    }
    if handshake_buf != HANDSHAKE_CLIENT_STR {
        return Err("Invalid handshake from client".into());
    }

    // Do not touch global connection/audio state until the complete response is visible
    // to the peer. A failed or stalled candidate handshake cannot disturb the active device.
    tokio::select! {
        _ = cancel_token.cancelled() => return Ok(()),
        result = timeout(HANDSHAKE_TIMEOUT, async {
            socket.write_all(HANDSHAKE_SERVER_STR).await?;
            socket.flush().await
        }) => {
            result.map_err(|_| IoError::new(ErrorKind::TimedOut, "handshake write timed out"))??;
        }
    }

    // The first framed message completes the protocol handshake. Modern clients send Connect with
    // their audio session ID; legacy clients' first control/audio frame decodes with no Connect.
    let first_message = tokio::select! {
        _ = cancel_token.cancelled() => return Ok(()),
        result = timeout(FRAME_READ_TIMEOUT, async {
            let mut header = [0u8; FRAME_HEADER_LEN];
            socket.read_exact(&mut header).await?;
            let payload_len = parse_frame_header(&header)?;
            let mut payload = vec![0u8; payload_len];
            socket.read_exact(&mut payload).await?;
            MessageWrapper::decode(payload.as_slice())
                .map_err(|e| IoError::new(ErrorKind::InvalidData, e))
        }) => result.map_err(|_| IoError::new(ErrorKind::TimedOut, "initial control frame timed out"))??,
    };
    let expected_session = match first_message.connect.as_ref() {
        Some(connect) => ExpectedAudioSession::Bound(connect.session_id),
        None => ExpectedAudioSession::UnboundLegacy,
    };

    #[cfg(windows)]
    let raw: RawSocketHandle = std::os::windows::io::AsRawSocket::as_raw_socket(&socket);
    #[cfg(unix)]
    let raw: RawSocketHandle = std::os::unix::io::AsRawFd::as_raw_fd(&socket);

    let connection_id = NEXT_CONNECTION_ID.fetch_add(1, Ordering::Relaxed);
    let (tx, mut rx) = tokio::sync::mpsc::channel::<MessageWrapper>(100);
    let takeover_token = CancellationToken::new();

    // Serialize reservation and publication so acknowledged candidates cannot reorder
    // SessionStarting relative to the active connection they publish.
    let _takeover_guard = takeover_lock.lock().await;
    let session_start_permit = tokio::select! {
        _ = cancel_token.cancelled() => return Ok(()),
        result = timeout(HANDSHAKE_TIMEOUT, audio_tx.clone().reserve_owned()) => {
            result
                .map_err(|_| IoError::new(ErrorKind::TimedOut, "audio pipeline reservation timed out"))?
                .map_err(|_| IoError::new(ErrorKind::BrokenPipe, "audio pipeline stopped"))?
        }
    };
    let old = {
        let mut active = active_connection.lock().await;
        let old = active.replace(ActiveConnection {
            sender: tx.clone(),
            raw_socket: raw,
            connection_id,
            takeover_token: takeover_token.clone(),
        });
        if let Some(old) = old.as_ref() {
            old.takeover_token.cancel();
            force_close_socket(old.raw_socket);
        }
        let epoch = if let Ok(mut active_audio) = active_audio_session.write() {
            let previous_epoch = match *active_audio {
                ActiveAudioSession::Inactive => 0,
                ActiveAudioSession::UnboundLegacy { epoch, .. }
                | ActiveAudioSession::Bound { epoch, .. } => epoch,
            };
            let epoch = previous_epoch.wrapping_add(1).max(1);
            *active_audio = match expected_session {
                ExpectedAudioSession::Inactive => ActiveAudioSession::Inactive,
                ExpectedAudioSession::UnboundLegacy => ActiveAudioSession::UnboundLegacy {
                    peer_ip: addr.ip(),
                    epoch,
                },
                ExpectedAudioSession::Bound(session_id) => ActiveAudioSession::Bound {
                    peer_ip: addr.ip(),
                    session_id,
                    epoch,
                },
            };
            epoch
        } else {
            return Err(IoError::new(ErrorKind::Other, "audio session lock poisoned").into());
        };
        session_start_permit.send(AudioStreamEvent::SessionStarting {
            expected: expected_session,
            epoch,
        });
        old
    };
    drop(old);
    drop(_takeover_guard);

    println!("Handshake successful with {}", addr);
    let device_info = DeviceInfo {
        name: "MicYou Mobile".to_string(),
        ip: addr.ip().to_string(),
        latency: 12,
    };
    let current_time = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    run_if_active(&active_connection, &takeover_token, connection_id, || {
        let _ = app_handle.emit("device-connected", device_info);
        stats.mark_tcp_connected(current_time);
    })
    .await;

    handle_message(
        first_message,
        &tx,
        &audio_tx,
        &stats,
        &app_handle,
        &takeover_token,
        connection_id,
        &active_connection,
        &active_audio_session,
        addr.ip(),
    )
    .await?;

    let (mut read_half, mut write_half) = socket.into_split();
    let writer_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            let payload = msg.encode_to_vec();
            if payload.len() > MAX_CONTROL_PAYLOAD_LEN {
                eprintln!("Outgoing control payload exceeds protocol limit");
                break;
            }
            let Ok(payload_len) = i32::try_from(payload.len()) else {
                break;
            };
            let frame_len = match FRAME_HEADER_LEN.checked_add(payload.len()) {
                Some(len) => len,
                None => break,
            };
            let mut frame = Vec::with_capacity(frame_len);
            frame.extend_from_slice(&PACKET_MAGIC.to_be_bytes());
            frame.extend_from_slice(&payload_len.to_be_bytes());
            frame.extend_from_slice(&payload);
            match timeout(FRAME_WRITE_TIMEOUT, write_half.write_all(&frame)).await {
                Ok(Ok(())) => {}
                Ok(Err(e)) => {
                    eprintln!("Write failed: {}", e);
                    break;
                }
                Err(_) => {
                    eprintln!("Control frame write timed out");
                    break;
                }
            }
        }
    });

    let tx_ping = tx.clone();
    let ping_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_millis(500));
        loop {
            interval.tick().await;
            let ping_msg = MessageWrapper {
                audio_packet: None,
                connect: None,
                mute: None,
                plugin_sync: None,
                ping: Some(micyou_protocol::micyou::PingMessage {
                    timestamp: std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap()
                        .as_millis() as i64,
                }),
                pong: None,
            };
            if tx_ping.send(ping_msg).await.is_err() {
                break;
            }
        }
    });

    let stats_emit = stats.clone();
    let app_handle_emit = app_handle.clone();
    let monitor_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_millis(1000));
        let mut warning_fired = false;
        loop {
            interval.tick().await;
            let buffer_duration = if mode == "usb" { 5 } else { 30 };
            let _ = app_handle_emit.emit("audio-metrics", stats_emit.to_metrics(buffer_duration));
            if mode == "wifi" {
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_millis() as u64;
                let tcp_time = stats_emit.get_tcp_connected_time();
                let last_udp = stats_emit.get_last_udp_time();
                if tcp_time > 0 && now.saturating_sub(tcp_time) > 5000 {
                    let time_since_udp = if last_udp == 0 {
                        now.saturating_sub(tcp_time)
                    } else {
                        now.saturating_sub(last_udp)
                    };
                    if time_since_udp > 10000 && !warning_fired {
                        let _ = app_handle_emit.emit("udp_audio_warning", ());
                        warning_fired = true;
                    } else if time_since_udp < 5000 && warning_fired {
                        warning_fired = false;
                    }
                }
            }
        }
    });
    let task_guard = TaskGuard::new(vec![writer_task, ping_task, monitor_task]);

    let reader = async {
        loop {
            let mut header = [0u8; FRAME_HEADER_LEN];
            let frame_result = timeout(FRAME_READ_TIMEOUT, async {
                read_half.read_exact(&mut header).await?;
                let payload_len = parse_frame_header(&header)?;
                // The parser guarantees this allocation never exceeds header + 1 MiB.
                let mut payload = vec![0u8; payload_len];
                read_half.read_exact(&mut payload).await?;
                Ok::<Vec<u8>, IoError>(payload)
            })
            .await
            .map_err(|_| IoError::new(ErrorKind::TimedOut, "control frame read timed out"))??;

            let message = MessageWrapper::decode(frame_result.as_slice())?;
            if takeover_token.is_cancelled() {
                break;
            }
            handle_message(
                message,
                &tx,
                &audio_tx,
                &stats,
                &app_handle,
                &takeover_token,
                connection_id,
                &active_connection,
                &active_audio_session,
                addr.ip(),
            )
            .await?;
        }
        #[allow(unreachable_code)]
        Ok::<(), Box<dyn Error + Send + Sync>>(())
    };

    let reader_result = tokio::select! {
        _ = cancel_token.cancelled() => Ok(()),
        result = reader => result,
    };
    task_guard.abort_and_wait().await;

    if clear_if_active(&active_connection, connection_id).await {
        if let Ok(mut active_audio) = active_audio_session.write() {
            *active_audio = ActiveAudioSession::default();
        }
        let _ = app_handle.emit("device-disconnected", ());
    }
    reader_result
}

async fn handle_message(
    msg: MessageWrapper,
    tx: &tokio::sync::mpsc::Sender<MessageWrapper>,
    audio_tx: &tokio::sync::mpsc::Sender<AudioStreamEvent>,
    stats: &Arc<crate::stats::NetworkStats>,
    app_handle: &AppHandle,
    takeover_token: &CancellationToken,
    connection_id: u64,
    active_connection: &SharedActiveConnection,
    active_audio_session: &SharedActiveAudioSession,
    peer_ip: std::net::IpAddr,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    if let Some(audio) = msg.audio_packet {
        if !validate_audio_packet(&audio) {
            return Ok(());
        }
        let AudioPacketAcceptance::Accepted { epoch } =
            try_accept_audio_packet(active_audio_session, peer_ip, &audio)
        else {
            return Ok(());
        };
        let permit = tokio::select! {
            biased;
            _ = takeover_token.cancelled() => return Ok(()),
            result = audio_tx.clone().reserve_owned() => match result {
                Ok(permit) => permit,
                Err(_) => return Ok(()),
            },
        };
        run_if_active(active_connection, takeover_token, connection_id, || {
            permit.send(AudioStreamEvent::Packet {
                packet: audio,
                epoch,
            });
        })
        .await;
    }
    if let Some(ping) = msg.ping {
        let pong_msg = MessageWrapper {
            audio_packet: None,
            connect: None,
            mute: None,
            plugin_sync: None,
            ping: None,
            pong: Some(micyou_protocol::micyou::PongMessage {
                timestamp: ping.timestamp,
            }),
        };
        let permit = tokio::select! {
            biased;
            _ = takeover_token.cancelled() => return Ok(()),
            result = tx.clone().reserve_owned() => match result {
                Ok(permit) => permit,
                Err(_) => return Ok(()),
            },
        };
        run_if_active(active_connection, takeover_token, connection_id, || {
            permit.send(pong_msg);
        })
        .await;
    }
    if let Some(pong) = msg.pong {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64;
        let rtt = now - pong.timestamp;
        if rtt >= 0 {
            run_if_active(active_connection, takeover_token, connection_id, || {
                stats.set_rtt(rtt)
            })
            .await;
        }
    }
    if let Some(mute) = msg.mute {
        run_if_active(active_connection, takeover_token, connection_id, || {
            println!("Received mute state: {}", mute.is_muted);
            let _ = app_handle.emit("mute-state-changed", mute.is_muted);
        })
        .await;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(payload_len: i32) -> [u8; FRAME_HEADER_LEN] {
        let mut header = [0; FRAME_HEADER_LEN];
        header[..4].copy_from_slice(&PACKET_MAGIC.to_be_bytes());
        header[4..].copy_from_slice(&payload_len.to_be_bytes());
        header
    }

    #[test]
    fn frame_header_rejects_negative_payload_length() {
        assert_eq!(
            parse_frame_header(&header(-1)).unwrap_err().kind(),
            ErrorKind::InvalidData
        );
    }

    #[test]
    fn frame_header_rejects_payload_over_limit() {
        assert_eq!(
            parse_frame_header(&header(MAX_CONTROL_PAYLOAD_LEN as i32 + 1))
                .unwrap_err()
                .kind(),
            ErrorKind::InvalidData
        );
    }

    #[test]
    fn frame_header_accepts_maximum_payload_length() {
        assert_eq!(
            parse_frame_header(&header(MAX_CONTROL_PAYLOAD_LEN as i32)).unwrap(),
            MAX_CONTROL_PAYLOAD_LEN
        );
    }

    #[tokio::test]
    async fn cancelled_takeover_token_rejects_side_effect() {
        let token = CancellationToken::new();
        let (sender, _receiver) = tokio::sync::mpsc::channel(1);
        let active = Arc::new(Mutex::new(Some(ActiveConnection {
            sender,
            raw_socket: 0 as RawSocketHandle,
            connection_id: 1,
            takeover_token: token.clone(),
        })));
        token.cancel();
        let side_effect_ran = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let side_effect_ran_clone = side_effect_ran.clone();

        assert!(
            !run_if_active(&active, &token, 1, move || {
                side_effect_ran_clone.store(true, Ordering::SeqCst);
            })
            .await
        );
        assert!(!side_effect_ran.load(Ordering::SeqCst));
    }

    #[tokio::test]
    async fn cleanup_session_state_takes_slot_before_cancel_and_only_uses_handle_once() {
        let token = CancellationToken::new();
        let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
        let raw = 42 as RawSocketHandle;
        let active_connection = Arc::new(Mutex::new(Some(ActiveConnection {
            sender,
            raw_socket: raw,
            connection_id: 1,
            takeover_token: token.clone(),
        })));
        let active_audio_session = Arc::new(std::sync::RwLock::new(
            ActiveAudioSession::UnboundLegacy {
                peer_ip: "127.0.0.1".parse().unwrap(),
                epoch: 1,
            },
        ));
        let shutdown_handles = Arc::new(std::sync::Mutex::new(Vec::new()));

        let shutdown_handles_first = shutdown_handles.clone();
        let active_connection_at_shutdown = active_connection.clone();
        let token_at_shutdown = token.clone();
        cleanup_session_state_with(
            &active_connection,
            &active_audio_session,
            move |handle| {
                assert!(active_connection_at_shutdown.try_lock().unwrap().is_none());
                assert!(token_at_shutdown.is_cancelled());
                shutdown_handles_first.lock().unwrap().push(handle);
            },
        )
        .await;

        let shutdown_handles_second = shutdown_handles.clone();
        cleanup_session_state_with(
            &active_connection,
            &active_audio_session,
            move |handle| shutdown_handles_second.lock().unwrap().push(handle),
        )
        .await;

        assert_eq!(*shutdown_handles.lock().unwrap(), vec![raw]);
        assert!(token.is_cancelled());
        assert!(active_connection.lock().await.is_none());
        assert!(matches!(
            *active_audio_session.read().unwrap(),
            ActiveAudioSession::Inactive
        ));
        assert!(receiver.recv().await.is_none());
    }
}
