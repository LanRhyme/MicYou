use tokio::net::{TcpListener, TcpStream};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use bytes::{BytesMut, Buf};
use prost::Message;
use std::error::Error;
use std::net::SocketAddr;
use tauri::{AppHandle, Emitter};
use serde::Serialize;
use crate::protocol::{PACKET_MAGIC, HANDSHAKE_CLIENT_STR, HANDSHAKE_SERVER_STR};
use crate::protocol::micyou::MessageWrapper;
use tokio_util::sync::CancellationToken;

#[derive(Serialize, Clone)]
pub struct DeviceInfo {
    pub name: String,
    pub ip: String,
    pub latency: u32,
}

pub async fn start_tcp_server(app_handle: AppHandle, port: u16, cancel_token: CancellationToken) -> Result<(), Box<dyn Error + Send + Sync>> {
    let listener = TcpListener::bind(format!("0.0.0.0:{}", port)).await?;
    println!("TCP Control Server listening on {}", port);

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
                        tokio::spawn(async move {
                            if let Err(e) = handle_client(socket, addr, app_handle_clone.clone()).await {
                                eprintln!("Client {} error: {}", addr, e);
                            }
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

async fn handle_client(mut socket: TcpStream, addr: SocketAddr, app_handle: AppHandle) -> Result<(), Box<dyn Error + Send + Sync>> {
    // 1. Handshake
    let mut handshake_buf = vec![0u8; HANDSHAKE_CLIENT_STR.len()];
    socket.read_exact(&mut handshake_buf).await?;
    if handshake_buf != HANDSHAKE_CLIENT_STR {
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

    let mut buffer = BytesMut::with_capacity(8192);

    // 2. Channel for writing
    let (tx, mut rx) = tokio::sync::mpsc::channel::<MessageWrapper>(100);
    let (mut read_half, mut write_half) = socket.into_split();

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
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(1));
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
                return Err("Invalid packet magic".into());
            }

            let payload_len = i32::from_be_bytes(buffer[4..8].try_into().unwrap()) as usize;
            
            if buffer.len() < 8 + payload_len {
                break; // Need more data
            }
            
            buffer.advance(8);
            let payload = buffer.split_to(payload_len);
            
            let message = MessageWrapper::decode(payload.freeze())?;
            handle_message(message, &tx).await?;
        }
    }

    writer_task.abort();
    ping_task.abort();

    Ok(())
}

async fn handle_message(msg: MessageWrapper, tx: &tokio::sync::mpsc::Sender<MessageWrapper>) -> Result<(), Box<dyn Error + Send + Sync>> {
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
    
    if let Some(mute) = msg.mute {
        println!("Received mute state: {}", mute.is_muted);
    }
    
    Ok(())
}
