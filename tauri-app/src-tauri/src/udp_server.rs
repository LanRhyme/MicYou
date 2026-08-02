use tokio::net::UdpSocket;

use micyou_protocol::micyou::MessageWrapper;
use micyou_protocol::UDP_PACKET_MAGIC;
use prost::Message;
use std::error::Error;
use std::net::IpAddr;
use std::sync::{Arc, RwLock};
use tokio::sync::mpsc::Sender;
use tokio_util::sync::CancellationToken;

use crate::audio_stream::{can_bind_legacy_packet, validate_audio_packet, AudioStreamEvent};
use micyou_protocol::micyou::AudioPacketMessageOrdered;

const UDP_HEADER_LEN: usize = 8;
// A protobuf wrapper around Android's <=1,400-byte PCM/FEC chunks. Keep datagrams
// below the UDP protocol maximum; nested audio buffers are validated separately.
pub const MAX_AUDIO_PAYLOAD_LEN: usize = 64 * 1024;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ActiveAudioSession {
    #[default]
    Inactive,
    UnboundLegacy {
        peer_ip: IpAddr,
        epoch: u64,
    },
    Bound {
        peer_ip: IpAddr,
        session_id: i64,
        epoch: u64,
    },
}

pub type SharedActiveAudioSession = Arc<RwLock<ActiveAudioSession>>;

fn parse_datagram(datagram: &[u8]) -> Option<&[u8]> {
    if datagram.len() < UDP_HEADER_LEN {
        return None;
    }
    let magic = i32::from_be_bytes(datagram[0..4].try_into().ok()?);
    if magic != UDP_PACKET_MAGIC {
        return None;
    }
    let payload_len_i32 = i32::from_be_bytes(datagram[4..8].try_into().ok()?);
    if payload_len_i32 < 0 {
        return None;
    }
    let payload_len = usize::try_from(payload_len_i32).ok()?;
    if payload_len > MAX_AUDIO_PAYLOAD_LEN {
        return None;
    }
    let end = UDP_HEADER_LEN.checked_add(payload_len)?;
    if end > datagram.len() {
        return None;
    }
    Some(&datagram[UDP_HEADER_LEN..end])
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AudioPacketAcceptance {
    Rejected,
    Accepted { epoch: u64 },
}

pub fn try_accept_audio_packet(
    active_audio_session: &SharedActiveAudioSession,
    source_ip: IpAddr,
    packet: &AudioPacketMessageOrdered,
) -> AudioPacketAcceptance {
    let Ok(mut active) = active_audio_session.write() else {
        return AudioPacketAcceptance::Rejected;
    };
    match *active {
        ActiveAudioSession::Inactive => AudioPacketAcceptance::Rejected,
        ActiveAudioSession::Bound {
            peer_ip,
            session_id,
            epoch,
        } => {
            if peer_ip == source_ip && session_id == packet.session_id {
                AudioPacketAcceptance::Accepted { epoch }
            } else {
                AudioPacketAcceptance::Rejected
            }
        }
        ActiveAudioSession::UnboundLegacy { peer_ip, epoch } => {
            if peer_ip != source_ip || !can_bind_legacy_packet(packet) {
                return AudioPacketAcceptance::Rejected;
            }
            *active = ActiveAudioSession::Bound {
                peer_ip,
                session_id: packet.session_id,
                epoch,
            };
            AudioPacketAcceptance::Accepted { epoch }
        }
    }
}

pub async fn start_udp_server(
    tx: Sender<AudioStreamEvent>,
    port: u16,
    bind_address: String,
    cancel_token: CancellationToken,
    stats: std::sync::Arc<crate::stats::NetworkStats>,
    active_audio_session: SharedActiveAudioSession,
    ready: tokio::sync::oneshot::Sender<Result<(), String>>,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let result = (|| -> Result<tokio::net::UdpSocket, Box<dyn Error + Send + Sync>> {
        let addr: std::net::SocketAddr = format!("{}:{}", bind_address, port).parse()?;
        let socket2 = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::DGRAM, None)?;
        if let Err(e) = socket2.set_recv_buffer_size(2 * 1024 * 1024) {
            eprintln!(
                "Warning: Failed to set UDP receive buffer size to 2MB: {}",
                e
            );
        }
        socket2.bind(&addr.into())?;
        socket2.set_nonblocking(true)?;
        let std_socket: std::net::UdpSocket = socket2.into();
        Ok(UdpSocket::from_std(std_socket)?)
    })();
    let socket = match result {
        Ok(socket) => socket,
        Err(error) => {
            let _ = ready.send(Err(error.to_string()));
            return Err(error);
        }
    };
    let _ = ready.send(Ok(()));
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

                let Some(payload) = parse_datagram(&buf[..len]) else {
                    continue;
                };
                match MessageWrapper::decode(payload) {
                    Ok(msg) => {
                        if let Some(audio_packet_ordered) = msg.audio_packet {
                            if !validate_audio_packet(&audio_packet_ordered) {
                                continue;
                            }
                            let AudioPacketAcceptance::Accepted { epoch } = try_accept_audio_packet(
                                &active_audio_session,
                                addr.ip(),
                                &audio_packet_ordered,
                            ) else {
                                continue;
                            };
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

                            match tx.try_send(AudioStreamEvent::Packet {
                                packet: audio_packet_ordered,
                                epoch,
                            }) {
                                Ok(()) | Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {}
                                Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
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

#[cfg(test)]
mod tests {
    use super::*;

    fn datagram(payload_len: i32, actual_payload: usize) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(UDP_HEADER_LEN + actual_payload);
        bytes.extend_from_slice(&UDP_PACKET_MAGIC.to_be_bytes());
        bytes.extend_from_slice(&payload_len.to_be_bytes());
        bytes.resize(UDP_HEADER_LEN + actual_payload, 7);
        bytes
    }

    #[test]
    fn parser_rejects_negative_length() {
        assert!(parse_datagram(&datagram(-1, 0)).is_none());
    }

    #[test]
    fn parser_rejects_over_limit_length() {
        assert!(parse_datagram(&datagram(MAX_AUDIO_PAYLOAD_LEN as i32 + 1, 0)).is_none());
    }

    #[test]
    fn parser_rejects_truncated_payload() {
        assert!(parse_datagram(&datagram(4, 3)).is_none());
    }

    #[test]
    fn parser_rejects_bad_magic() {
        let mut bytes = datagram(0, 0);
        bytes[0] ^= 1;
        assert!(parse_datagram(&bytes).is_none());
    }

    #[test]
    fn parser_accepts_maximum_payload_boundary() {
        let bytes = datagram(MAX_AUDIO_PAYLOAD_LEN as i32, MAX_AUDIO_PAYLOAD_LEN);
        assert_eq!(parse_datagram(&bytes).unwrap().len(), MAX_AUDIO_PAYLOAD_LEN);
    }

    fn accepted(result: AudioPacketAcceptance) -> bool {
        matches!(result, AudioPacketAcceptance::Accepted { .. })
    }

    fn packet(
        sequence_number: i32,
        fec_sequence_number: i32,
        session_id: i64,
    ) -> AudioPacketMessageOrdered {
        AudioPacketMessageOrdered {
            sequence_number,
            audio_packet: None,
            timestamp: 0,
            fec_buffer: Vec::new(),
            fec_sequence_number,
            session_id,
            fec_packet_lengths: Vec::new(),
        }
    }

    #[test]
    fn modern_and_inactive_filters_are_strict() {
        let ip: IpAddr = "192.168.1.2".parse().unwrap();
        let other: IpAddr = "192.168.1.3".parse().unwrap();
        let active = Arc::new(RwLock::new(ActiveAudioSession::Bound {
            peer_ip: ip,
            session_id: 202,
            epoch: 7,
        }));
        assert_eq!(
            try_accept_audio_packet(&active, ip, &packet(99, -1, 202)),
            AudioPacketAcceptance::Accepted { epoch: 7 }
        );
        assert!(!accepted(try_accept_audio_packet(
            &active,
            ip,
            &packet(0, -1, 101)
        )));
        assert!(!accepted(try_accept_audio_packet(
            &active,
            other,
            &packet(0, -1, 202)
        )));

        *active.write().unwrap() = ActiveAudioSession::Inactive;
        assert!(!accepted(try_accept_audio_packet(
            &active,
            ip,
            &packet(0, -1, 202)
        )));
    }

    #[test]
    fn legacy_first_low_packet_binds_non_zero_id_and_preserves_epoch() {
        let ip: IpAddr = "192.168.1.2".parse().unwrap();
        let active = Arc::new(RwLock::new(ActiveAudioSession::UnboundLegacy {
            peer_ip: ip,
            epoch: 9,
        }));

        assert_eq!(
            try_accept_audio_packet(&active, ip, &packet(0, -1, 202)),
            AudioPacketAcceptance::Accepted { epoch: 9 }
        );
        assert_eq!(
            *active.read().unwrap(),
            ActiveAudioSession::Bound {
                peer_ip: ip,
                session_id: 202,
                epoch: 9
            }
        );
        assert!(!accepted(try_accept_audio_packet(
            &active,
            ip,
            &packet(1, -1, 101)
        )));
    }

    #[test]
    fn legacy_high_or_fec_packet_cannot_bind_but_session_zero_can() {
        let ip: IpAddr = "192.168.1.2".parse().unwrap();
        let active = Arc::new(RwLock::new(ActiveAudioSession::UnboundLegacy {
            peer_ip: ip,
            epoch: 3,
        }));

        assert!(!accepted(try_accept_audio_packet(
            &active,
            ip,
            &packet(99, -1, 101)
        )));
        assert!(!accepted(try_accept_audio_packet(
            &active,
            ip,
            &packet(0, 12, 101)
        )));
        assert_eq!(
            *active.read().unwrap(),
            ActiveAudioSession::UnboundLegacy {
                peer_ip: ip,
                epoch: 3
            }
        );
        assert_eq!(
            try_accept_audio_packet(&active, ip, &packet(0, -1, 0)),
            AudioPacketAcceptance::Accepted { epoch: 3 }
        );
    }
}
