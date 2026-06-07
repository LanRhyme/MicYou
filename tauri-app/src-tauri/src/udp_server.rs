use tokio::net::UdpSocket;

use prost::Message;
use std::error::Error;
use crate::protocol::UDP_PACKET_MAGIC;
use tokio::sync::mpsc::Sender;
use crate::protocol::micyou::{AudioPacketMessageOrdered, MessageWrapper};
use tokio_util::sync::CancellationToken;

pub async fn start_udp_server(tx: Sender<AudioPacketMessageOrdered>, port: u16, cancel_token: CancellationToken, stats: std::sync::Arc<crate::stats::NetworkStats>) -> Result<(), Box<dyn Error + Send + Sync>> {
    let socket = UdpSocket::bind(format!("0.0.0.0:{}", port)).await?;
    println!("UDP Audio Server listening on {}", port);

    let mut buf = vec![0u8; 65535];

    let mut last_seq: Option<i32> = None;
    let mut total_packets: u64 = 0;
    let mut lost_packets: u64 = 0;
    let mut jitter: f64 = 0.0;
    let mut last_transit: i64 = 0;

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => {
                println!("UDP Server cancelled");
                break;
            }
            recv_result = socket.recv_from(&mut buf) => {
                let (len, addr) = match recv_result {
                    Ok(res) => res,
                    Err(e) => {
                        eprintln!("UDP recv error: {}", e);
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
                            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
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
                                // Bitrate estimation based on payload len (simplified)
                                let bps = (payload.len() as u32) * 8 * (audio_info.sample_rate as u32) / 480; // approximate assuming ~10ms packets
                                stats.set_audio_info(audio_info.sample_rate as u32, bps);
                            }

                            if let Err(e) = tx.send(audio_packet_ordered).await {
                                eprintln!("Failed to send audio packet to channel: {}", e);
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("Failed to decode UDP payload from {}: {}", addr, e);
                    }
                }
            }
        }
    }
    Ok(())
}
