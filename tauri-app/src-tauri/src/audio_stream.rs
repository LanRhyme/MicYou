use micyou_protocol::micyou::AudioPacketMessageOrdered;

// Android reads frame-aligned chunks capped at 1,400 bytes (AudioEngine.kt), and its
// 12-packet XOR FEC parity is the same size as the largest chunk. 64 KiB therefore
// leaves over 45x compatibility headroom without permitting MiB-sized nested blobs.
pub const MAX_AUDIO_BUFFER_LEN: usize = 64 * 1024;
const MIN_SAMPLE_RATE: i32 = 8_000;
const MAX_SAMPLE_RATE: i32 = 192_000;
const MAX_CHANNEL_COUNT: i32 = 2;

pub fn validate_audio_packet(packet: &AudioPacketMessageOrdered) -> bool {
    if packet.fec_buffer.len() > MAX_AUDIO_BUFFER_LEN {
        return false;
    }
    let Some(audio) = packet.audio_packet.as_ref() else {
        return false;
    };
    let bytes_per_sample = match audio.audio_format {
        2 => 2, // PCM 16-bit
        3 => 1, // PCM 8-bit
        4 => 4, // PCM float
        6 => 3, // PCM 24-bit
        _ => return false,
    };
    let frame_size = bytes_per_sample * audio.channel_count as usize;
    (MIN_SAMPLE_RATE..=MAX_SAMPLE_RATE).contains(&audio.sample_rate)
        && (1..=MAX_CHANNEL_COUNT).contains(&audio.channel_count)
        && !audio.buffer.is_empty()
        && audio.buffer.len() <= MAX_AUDIO_BUFFER_LEN
        && frame_size > 0
        && audio.buffer.len() % frame_size == 0
}

pub fn audio_payload_len(packet: &AudioPacketMessageOrdered) -> usize {
    packet
        .audio_packet
        .as_ref()
        .map_or(0, |audio| audio.buffer.len())
        .saturating_add(packet.fec_buffer.len())
}

/// Transport-level audio session state. `UnboundLegacy` is distinct from both an
/// inactive transport and a session explicitly bound to ID zero.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ExpectedAudioSession {
    #[default]
    Inactive,
    UnboundLegacy,
    Bound(i64),
}

/// Legacy streams restart their sequence counter at zero. Keeping this window
/// below the first normal FEC group boundary prevents delayed packets (including
/// the group-zero parity packet) from claiming a newly connected legacy stream.
pub const MAX_LEGACY_START_SEQUENCE: i32 = 4;

pub fn can_bind_legacy_packet(packet: &AudioPacketMessageOrdered) -> bool {
    (0..=MAX_LEGACY_START_SEQUENCE).contains(&packet.sequence_number)
        && packet.fec_sequence_number <= 0
}

#[derive(Debug)]
pub enum AudioStreamEvent {
    SessionStarting {
        expected: ExpectedAudioSession,
        epoch: u64,
    },
    Packet {
        packet: AudioPacketMessageOrdered,
        epoch: u64,
    },
}

#[cfg(test)]
mod tests {
    use super::*;
    use micyou_protocol::micyou::{AudioPacketMessage, MessageWrapper};
    use prost::Message;

    fn packet(buffer_len: usize) -> AudioPacketMessageOrdered {
        AudioPacketMessageOrdered {
            sequence_number: 0,
            audio_packet: Some(AudioPacketMessage {
                buffer: vec![0; buffer_len],
                sample_rate: 48_000,
                channel_count: 1,
                audio_format: 2,
            }),
            timestamp: 0,
            fec_buffer: Vec::new(),
            fec_sequence_number: -1,
            session_id: 1,
        }
    }

    #[test]
    fn decoded_protobuf_with_oversized_nested_audio_buffer_is_rejected() {
        let encoded = MessageWrapper {
            audio_packet: Some(packet(MAX_AUDIO_BUFFER_LEN + 2)),
            ..Default::default()
        }
        .encode_to_vec();
        let decoded = MessageWrapper::decode(encoded.as_slice()).unwrap();
        assert!(!validate_audio_packet(
            decoded.audio_packet.as_ref().unwrap()
        ));
    }

    #[test]
    fn normal_audio_and_fec_payloads_are_accepted() {
        let normal = packet(1_400);
        assert!(validate_audio_packet(&normal));
        let mut fec = packet(1_400);
        fec.fec_sequence_number = 12;
        fec.fec_buffer = vec![0; 1_400];
        assert!(validate_audio_packet(&fec));
    }

    #[test]
    fn omitted_proto3_fec_sequence_decodes_as_zero() {
        let encoded = MessageWrapper {
            audio_packet: Some(AudioPacketMessageOrdered {
                sequence_number: 12,
                fec_sequence_number: 0,
                ..packet(2)
            }),
            ..Default::default()
        }
        .encode_to_vec();
        let decoded = MessageWrapper::decode(encoded.as_slice()).unwrap();

        assert_eq!(decoded.audio_packet.unwrap().fec_sequence_number, 0);
    }

    #[test]
    fn invalid_audio_metadata_or_alignment_is_rejected() {
        let mut invalid = packet(1_400);
        invalid.audio_packet.as_mut().unwrap().channel_count = 99;
        assert!(!validate_audio_packet(&invalid));
        invalid.audio_packet.as_mut().unwrap().channel_count = 1;
        invalid.audio_packet.as_mut().unwrap().buffer.push(0);
        assert!(!validate_audio_packet(&invalid));
    }
}
