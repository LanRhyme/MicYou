use tokio::net::UdpSocket;

use micyou_protocol::micyou::{AudioPacketMessageOrdered, MessageWrapper};
use micyou_protocol::UDP_PACKET_MAGIC;
use prost::Message;
use std::error::Error;
use tokio::sync::mpsc::Sender;
use tokio_util::sync::CancellationToken;

pub async fn start_udp_server(
    tx: Sender<AudioPacketMessageOrdered>,
    port: u16,
    bind_address: String,
    cancel_token: CancellationToken,
    stats: std::sync::Arc<crate::network::stats::NetworkStats>,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let addr: std::net::SocketAddr = format!("{}:{}", bind_address, port).parse()?;
    let socket2 = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::DGRAM, None)?;
    if let Err(e) = socket2.set_recv_buffer_size(2 * 1024 * 1024) {
        log::warn!(target: "udp", "Failed to set UDP receive buffer size to 2MB: {}", e);
    }
    socket2.bind(&addr.into())?;
    socket2.set_nonblocking(true)?;
    let std_socket: std::net::UdpSocket = socket2.into();
    let socket = UdpSocket::from_std(std_socket)?;
    log::info!(target: "udp", "UDP Audio Server listening on {}", port);

    let mut buf = vec![0u8; 65535];

    let mut last_seq: Option<i32> = None;
    let mut total_packets: u64 = 0;
    let mut lost_packets: u64 = 0;
    let mut jitter: f64 = 0.0;
    let mut last_transit: i64 = 0;
    let mut total_bytes: u64 = 0;
    let mut last_bitrate_ts: u64 = 0;
    let mut avg_bitrate: f64 = 0.0;

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => {
                log::info!(target: "udp", "UDP Server cancelled");
                break;
            }
            recv_result = socket.recv_from(&mut buf) => {
                let (len, addr) = match recv_result {
                    Ok(res) => res,
                    Err(e) => {
                        log::error!(target: "udp", "UDP recv error: {}", e);
                        continue;
                    }
                };

                if len < 8 {
                    continue;
                }

                let magic = i32::from_be_bytes(buf[0..4].try_into().unwrap());
                if magic != UDP_PACKET_MAGIC {
                    continue;
                }

                let payload_len = i32::from_be_bytes(buf[4..8].try_into().unwrap()) as usize;
                if len < 8 + payload_len {
                    continue;
                }

                let payload = &buf[8..8 + payload_len];
                match MessageWrapper::decode(payload) {
                    Ok(msg) => {
                        if let Some(audio_packet_ordered) = msg.audio_packet {
                            let now = crate::util::now_millis() as u64;
                            stats.mark_udp_received(now);

                            let seq = audio_packet_ordered.sequence_number;
                            if let Some(l_seq) = last_seq {
                                if seq > l_seq + 1 {
                                    lost_packets += (seq - l_seq - 1) as u64;
                                }
                            }
                            last_seq = Some(seq);
                            total_packets += 1;

                            if total_packets > 0 {
                                stats.set_loss_rate((lost_packets as f64 / total_packets as f64) * 100.0);
                            }

                            let transit = now as i64 - audio_packet_ordered.timestamp;
                            if last_transit != 0 {
                                let d = (transit - last_transit).abs() as f64;
                                jitter += (d - jitter) / 16.0;
                                stats.set_jitter(jitter);
                            }
                            last_transit = transit;

                            if let Some(ref audio_info) = audio_packet_ordered.audio_packet {
                                    // Bitrate estimation: bytes over time with exponential moving average.
                                    total_bytes += payload.len() as u64;
                                    if last_bitrate_ts == 0 {
                                        last_bitrate_ts = now;
                                    } else {
                                        let elapsed = now.saturating_sub(last_bitrate_ts);
                                        if elapsed >= 500 {
                                            let instant_bps =
                                                (total_bytes as f64 * 8.0 * 1000.0) / elapsed as f64;
                                            avg_bitrate += (instant_bps - avg_bitrate) / 8.0;
                                            total_bytes = 0;
                                            last_bitrate_ts = now;
                                        }
                                    }
                                    stats.set_audio_info(
                                        audio_info.sample_rate as u32,
                                        avg_bitrate as u32,
                                    );
                                }

                            if let Err(e) = tx.send(audio_packet_ordered).await {
                                log::error!(target: "udp", "Failed to send audio packet to channel: {}", e);
                            }
                        }
                    }
                    Err(e) => {
                        log::error!(target: "udp", "Failed to decode UDP payload from {}: {}", addr, e);
                    }
                }
            }
        }
    }
    Ok(())
}
