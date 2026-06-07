use tokio::net::UdpSocket;

use prost::Message;
use std::error::Error;
use crate::protocol::UDP_PACKET_MAGIC;
use tokio::sync::mpsc::Sender;
use crate::protocol::micyou::{AudioPacketMessageOrdered, MessageWrapper};
use tokio_util::sync::CancellationToken;

pub async fn start_udp_server(tx: Sender<AudioPacketMessageOrdered>, port: u16, cancel_token: CancellationToken) -> Result<(), Box<dyn Error + Send + Sync>> {
    let socket = UdpSocket::bind(format!("0.0.0.0:{}", port)).await?;
    println!("UDP Audio Server listening on {}", port);

    let mut buf = vec![0u8; 65535];

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
