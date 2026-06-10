use tokio::net::{TcpListener, TcpStream};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use bytes::{BytesMut, Buf};
use prost::Message;
use std::error::Error;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::Mutex;
use tauri::{AppHandle, Emitter};
use serde::Serialize;
use crate::protocol::{PACKET_MAGIC, HANDSHAKE_CLIENT_STR, HANDSHAKE_SERVER_STR};
use crate::protocol::micyou::{MessageWrapper, AudioPacketMessageOrdered};
use tokio_util::sync::CancellationToken;

#[cfg(windows)]
type RawSocketHandle = std::os::windows::io::RawSocket;
#[cfg(unix)]
type RawSocketHandle = std::os::unix::io::RawFd;

#[derive(Serialize, Clone)]
pub struct DeviceInfo {
    pub name: String,
    pub ip: String,
    pub latency: u32,
}

pub async fn start_tcp_server(app_handle: AppHandle, port: u16, bind_address: String, cancel_token: CancellationToken, audio_tx: tokio::sync::mpsc::Sender<AudioPacketMessageOrdered>, stats: std::sync::Arc<crate::stats::NetworkStats>, mode: String, connection_tx: Arc<Mutex<Option<tokio::sync::mpsc::Sender<MessageWrapper>>>>, active_socket_handle: Arc<Mutex<Option<RawSocketHandle>>>) -> Result<(), Box<dyn Error + Send + Sync>> {
    let listener = TcpListener::bind(format!("{}:{}", bind_address, port)).await?;
    println!("TCP Control Server listening on {}:{}", bind_address, port);

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => {
                println!("TCP Server cancelled");
                break;
            }
            accept_result = listener.accept() => {
                match accept_result {
                    Ok((socket, addr)) => {
                        println!("New client connected: {}", addr);
                        let app_handle_clone = app_handle.clone();
                        let audio_tx_clone = audio_tx.clone();
                        let stats_clone = stats.clone();
                        let mode_clone = mode.clone();
                        let connection_tx_clone = connection_tx.clone();
                        let active_handle_clone = active_socket_handle.clone();
                        let active_handle_cleanup = active_socket_handle.clone();
                        tokio::spawn(async move {
                            if let Err(e) = handle_client(socket, addr, app_handle_clone.clone(), audio_tx_clone, stats_clone, mode_clone, connection_tx_clone, active_handle_clone).await {
                                eprintln!("Client {} error: {}", addr, e);
                            }
                            // Clear handle on disconnect
                            active_handle_cleanup.lock().await.take();
                            println!("Client {} disconnected", addr);
                            let _ = app_handle_clone.emit("device-disconnected", ());
                        });
                    }
                    Err(e) => {
                        eprintln!("Failed to accept TCP connection: {}", e);
                    }
                }
            }
        }
    }
    Ok(())
}

/// Force-close the active TCP socket from another task.
/// This calls OS-level shutdown so any pending read/write in handle_client
/// will immediately fail, causing the task to exit and clean up.
pub fn force_close_socket(raw: RawSocketHandle) {
    unsafe {
        #[cfg(windows)]
        {
            winapi::um::winsock2::shutdown(raw as winapi::um::winsock2::SOCKET, 2);
        }
        #[cfg(unix)]
        {
            libc::shutdown(raw, libc::SHUT_RDWR);
        }
    }
}

async fn handle_client(mut socket: TcpStream, addr: SocketAddr, app_handle: AppHandle, audio_tx: tokio::sync::mpsc::Sender<AudioPacketMessageOrdered>, stats: std::sync::Arc<crate::stats::NetworkStats>, mode: String, connection_tx: Arc<Mutex<Option<tokio::sync::mpsc::Sender<MessageWrapper>>>>, active_socket_handle: Arc<Mutex<Option<RawSocketHandle>>>) -> Result<(), Box<dyn Error + Send + Sync>> {
    // 1. Handshake
    let mut handshake_buf = vec![0u8; HANDSHAKE_CLIENT_STR.len()];
    socket.read_exact(&mut handshake_buf).await?;
    if handshake_buf != HANDSHAKE_CLIENT_STR {
        eprintln!("Invalid handshake from client: {:?}", handshake_buf);
        return Err("Invalid handshake from client".into());
    }
    
    // Send Server Handshake
    socket.write_all(HANDSHAKE_SERVER_STR).await?;
    println!("Handshake successful with {}", addr);

    let _ = app_handle.emit("device-connected", DeviceInfo {
        name: "MicYou Mobile".to_string(),
        ip: addr.ip().to_string(),
        latency: 12,
    });

    let current_time = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
    stats.mark_tcp_connected(current_time);

    let mut buffer = BytesMut::with_capacity(8192);

    // 2. Channel for writing
    let (tx, mut rx) = tokio::sync::mpsc::channel::<MessageWrapper>(100);
    {
        let mut lock = connection_tx.lock().await;
        *lock = Some(tx.clone());
    }

    // Extract raw socket handle BEFORE into_split() consumes the stream.
    // The OS socket stays alive via the split halves; stop_server can call
    // force_close_socket() on the raw handle to force-close the connection.
    #[cfg(windows)]
    let raw: RawSocketHandle = std::os::windows::io::AsRawSocket::as_raw_socket(&socket);
    #[cfg(unix)]
    let raw: RawSocketHandle = std::os::unix::io::AsRawFd::as_raw_fd(&socket);
    active_socket_handle.lock().await.replace(raw);

    let (mut read_half, mut write_half) = socket.into_split();

    let _ = app_handle.emit("device-connected", DeviceInfo {
        name: "MicYou Mobile".to_string(),
        ip: addr.ip().to_string(),
        latency: 12,
    });

    let current_time = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
    stats.mark_tcp_connected(current_time);

    let mut buffer = BytesMut::with_capacity(8192);

    // 2. Channel for writing
    let (tx, mut rx) = tokio::sync::mpsc::channel::<MessageWrapper>(100);
    {
        let mut lock = connection_tx.lock().await;
        *lock = Some(tx.clone());
    }

    // 3. Writer loop
    let writer_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            let mut payload = BytesMut::new();
            if let Err(e) = msg.encode(&mut payload) {
                eprintln!("Failed to encode message: {}", e);
                break;
            }
            
            let mut frame = BytesMut::with_capacity(8 + payload.len());
            frame.extend_from_slice(&PACKET_MAGIC.to_be_bytes());
            frame.extend_from_slice(&(payload.len() as i32).to_be_bytes());
            frame.extend_from_slice(&payload);
            
            if let Err(e) = write_half.write_all(&frame).await {
                eprintln!("Write failed: {}", e);
                break;
            }
        }
    });

    // 4. Ping loop
    let tx_ping = tx.clone();
    let ping_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(500));
        loop {
            interval.tick().await;
            let ping_msg = MessageWrapper {
                audio_packet: None,
                connect: None,
                mute: None,
                plugin_sync: None,
                ping: Some(crate::protocol::micyou::PingMessage {
                    timestamp: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64,
                }),
                pong: None,
            };
            if tx_ping.send(ping_msg).await.is_err() {
                break;
            }
        }
    });

    // Stats emission and UDP warning loop
    let stats_emit = stats.clone();
    let app_handle_emit = app_handle.clone();
    let monitor_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(1000));
        let mut warning_fired = false;
        loop {
            interval.tick().await;
            
            // Emulate realistic buffer duration: TCP/USB doesn't need a jitter buffer, so it's ~0-10ms. WiFi might use 30-50ms.
            let buffer_duration = if mode == "usb" { 5 } else { 30 };
            let metrics = stats_emit.to_metrics(buffer_duration); 
            let _ = app_handle_emit.emit("audio-metrics", metrics);

            // Check UDP timeout only for Wi-Fi mode
            if mode == "wifi" {
                let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
                let tcp_time = stats_emit.get_tcp_connected_time();
                let last_udp = stats_emit.get_last_udp_time();
                
                if tcp_time > 0 && (now.saturating_sub(tcp_time)) > 5000 {
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

    // 5. Control Loop (Reader)
    loop {
        let bytes_read = read_half.read_buf(&mut buffer).await?;
        if bytes_read == 0 {
            break; // connection closed
        }

        while buffer.len() >= 8 {
            let magic = i32::from_be_bytes(buffer[0..4].try_into().unwrap());
            if magic != PACKET_MAGIC {
                // Abort all on error
                writer_task.abort();
                ping_task.abort();
                eprintln!("Invalid packet magic received: {}", magic);
                return Err("Invalid packet magic".into());
            }

            let payload_len = i32::from_be_bytes(buffer[4..8].try_into().unwrap()) as usize;
            
            if buffer.len() < 8 + payload_len {
                break; // Need more data
            }
            
            buffer.advance(8);
            let payload = buffer.split_to(payload_len);
            
            let message = MessageWrapper::decode(payload.freeze())?;
            handle_message(message, &tx, &audio_tx, &stats, &app_handle).await?;
        }
    }

    writer_task.abort();
    ping_task.abort();
    monitor_task.abort();

    {
        let mut lock = connection_tx.lock().await;
        *lock = None;
    }

    Ok(())
}

async fn handle_message(msg: MessageWrapper, tx: &tokio::sync::mpsc::Sender<MessageWrapper>, audio_tx: &tokio::sync::mpsc::Sender<AudioPacketMessageOrdered>, stats: &std::sync::Arc<crate::stats::NetworkStats>, app_handle: &AppHandle) -> Result<(), Box<dyn Error + Send + Sync>> {
    if let Some(audio) = msg.audio_packet {
        let _ = audio_tx.send(audio).await;
        // Don't return here, message might contain ping/mute too, although unlikely
    }

    if let Some(ping) = msg.ping {
        let pong_msg = MessageWrapper {
            audio_packet: None,
            connect: None,
            mute: None,
            plugin_sync: None,
            ping: None,
            pong: Some(crate::protocol::micyou::PongMessage {
                timestamp: ping.timestamp,
            }),
        };
        
        let _ = tx.send(pong_msg).await;
    }

    if let Some(pong) = msg.pong {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64;
        let rtt = now - pong.timestamp;
        if rtt >= 0 {
            stats.set_rtt(rtt);
        }
    }
    
    if let Some(mute) = msg.mute {
        println!("Received mute state: {}", mute.is_muted);
        let _ = app_handle.emit("mute-state-changed", mute.is_muted);
    }
    
    Ok(())
}
