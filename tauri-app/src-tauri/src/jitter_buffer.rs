use crate::audio_stream::{audio_payload_len, can_bind_legacy_packet, ExpectedAudioSession};
use micyou_protocol::micyou::{AudioPacketMessage, AudioPacketMessageOrdered};
use std::collections::{BTreeMap, HashSet, VecDeque};

const MAX_RETIRED_SESSION_IDS: usize = 32;
// Android emits about 7 ms packets and prebuffers 15; 128 packets is under one second
// of latency while still leaving ample reordering/FEC headroom.
const MAX_BUFFERED_PACKETS: usize = 128;
const MAX_FORWARD_SEQUENCE_DISTANCE: i32 = (MAX_BUFFERED_PACKETS as i32) - 1;
const MAX_PLAYED_PACKETS: usize = 64;
const MAX_PLAYED_FEC_GROUPS: usize = 2;
// At the 64 KiB compatibility packet limit this permits at most 64 payloads, and
// normal 1,400-byte Android frames use only ~180 KiB at the packet-count ceiling.
const MAX_BUFFERED_PAYLOAD_BYTES: usize = 4 * 1024 * 1024;

struct PlayedPacketReference {
    timestamp: i64,
    session_id: i64,
    sample_rate: i32,
    channel_count: i32,
    audio_format: i32,
}

struct PlayedFecGroup {
    xor_buffer: Vec<u8>,
    sequences: HashSet<i32>,
    reference: PlayedPacketReference,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FecLayout {
    ExplicitMarker,
    LegacyHeuristic,
}

pub struct JitterBuffer {
    buffer: BTreeMap<i32, AudioPacketMessageOrdered>,
    fec_packets: BTreeMap<i32, AudioPacketMessageOrdered>,
    played_packets: HashSet<i32>,
    played_audio_packets: BTreeMap<i32, AudioPacketMessageOrdered>,
    played_fec_groups: BTreeMap<i32, PlayedFecGroup>,
    payload_bytes: usize,

    expected_sequence_number: i32,
    initialized: bool,
    fec_group_size: i32,
    prebuffered: bool,
    current_session_id: i64,
    retired_session_ids: VecDeque<i64>,
    expected_session: ExpectedAudioSession,
    fec_layout: FecLayout,
    current_epoch: u64,
}

impl JitterBuffer {
    pub fn new(fec_group_size: i32) -> Self {
        Self {
            buffer: BTreeMap::new(),
            fec_packets: BTreeMap::new(),
            played_packets: HashSet::new(),
            played_audio_packets: BTreeMap::new(),
            played_fec_groups: BTreeMap::new(),
            payload_bytes: 0,
            expected_sequence_number: 0,
            initialized: false,
            fec_group_size,
            prebuffered: false,
            current_session_id: 0,
            retired_session_ids: VecDeque::with_capacity(MAX_RETIRED_SESSION_IDS),
            expected_session: ExpectedAudioSession::Inactive,
            fec_layout: FecLayout::ExplicitMarker,
            current_epoch: 0,
        }
    }

    fn reset(&mut self) {
        self.buffer.clear();
        self.fec_packets.clear();
        self.played_packets.clear();
        self.played_audio_packets.clear();
        self.played_fec_groups.clear();
        self.payload_bytes = 0;
        self.expected_sequence_number = 0;
        self.initialized = false;
        self.prebuffered = false;
    }

    fn retire_session(&mut self, session_id: i64) {
        if session_id == 0 || self.retired_session_ids.contains(&session_id) {
            return;
        }
        self.retired_session_ids.push_back(session_id);
        if self.retired_session_ids.len() > MAX_RETIRED_SESSION_IDS {
            self.retired_session_ids.pop_front();
        }
    }

    pub fn prepare_transport_session(&mut self, expected: ExpectedAudioSession) {
        let epoch = self.current_epoch.wrapping_add(1);
        self.prepare_transport_session_epoch(expected, epoch);
    }

    pub fn prepare_transport_session_epoch(&mut self, expected: ExpectedAudioSession, epoch: u64) {
        self.retire_session(self.current_session_id);
        self.reset();
        self.current_session_id = 0;
        self.expected_session = expected;
        self.fec_layout = match expected {
            ExpectedAudioSession::UnboundLegacy => FecLayout::LegacyHeuristic,
            ExpectedAudioSession::Inactive | ExpectedAudioSession::Bound(_) => {
                FecLayout::ExplicitMarker
            }
        };
        self.current_epoch = epoch;
    }

    fn begin_transport_session(&mut self, session_id: i64) {
        self.current_session_id = session_id;
        self.expected_session = ExpectedAudioSession::Bound(session_id);
    }

    pub fn push(&mut self, packet: AudioPacketMessageOrdered) {
        self.push_epoch(packet, self.current_epoch);
    }

    pub fn push_epoch(&mut self, packet: AudioPacketMessageOrdered, epoch: u64) {
        // A packet accepted by UDP before takeover may arrive after SessionStarting.
        if epoch != self.current_epoch || packet.sequence_number < 0 {
            return;
        }

        let packet_session_id = packet.session_id;
        match self.expected_session {
            ExpectedAudioSession::Inactive => return,
            ExpectedAudioSession::UnboundLegacy => {
                if !can_bind_legacy_packet(&packet) {
                    return;
                }
                self.begin_transport_session(packet_session_id);
            }
            ExpectedAudioSession::Bound(expected) => {
                if packet_session_id != expected {
                    return;
                }
                self.current_session_id = expected;
            }
        }

        // Proto3 decodes an omitted fec_sequence_number as zero. Modern handshakes
        // guarantee a non-empty marker on parity packets, so never apply legacy sequence
        // heuristics there. A legacy layout remains legacy even when its first packet binds
        // a non-zero session ID.
        let is_fec = match self.fec_layout {
            FecLayout::ExplicitMarker => !packet.fec_buffer.is_empty(),
            FecLayout::LegacyHeuristic => {
                packet.fec_sequence_number > 0
                    || (packet.fec_sequence_number == 0
                        && packet.sequence_number == self.fec_group_size)
            }
        };

        if is_fec {
            let packet_bytes = audio_payload_len(&packet);
            let replaced_bytes = self
                .fec_packets
                .get(&packet.fec_sequence_number)
                .map_or(0, audio_payload_len);
            if self
                .payload_bytes
                .saturating_sub(replaced_bytes)
                .saturating_add(packet_bytes)
                > MAX_BUFFERED_PAYLOAD_BYTES
            {
                return;
            }
            let key = packet.fec_sequence_number;
            if let Some(old) = self.fec_packets.insert(key, packet) {
                self.payload_bytes = self.payload_bytes.saturating_sub(audio_payload_len(&old));
            }
            self.payload_bytes += packet_bytes;

            // Parity can arrive after part of its group has already been popped. Build
            // the summary now from the bounded played packet history using this explicit
            // group boundary.
            self.played_fec_groups.remove(&key);
            let played_in_group: Vec<_> = self
                .played_audio_packets
                .range(key..key.saturating_add(self.fec_group_size))
                .map(|(_, played)| played.clone())
                .collect();
            for played in &played_in_group {
                self.add_played_to_group(key, played);
            }
            self.trim_played_history();
            while self.fec_packets.len() > 10 {
                if let Some(key) = self.fec_packets.keys().next().copied() {
                    if let Some(old) = self.fec_packets.remove(&key) {
                        self.payload_bytes =
                            self.payload_bytes.saturating_sub(audio_payload_len(&old));
                    }
                }
            }
            return;
        }

        if !self.initialized {
            self.expected_sequence_number = packet.sequence_number;
            self.initialized = true;
        }

        if packet.sequence_number < self.expected_sequence_number
            || packet.sequence_number
                > self
                    .expected_sequence_number
                    .saturating_add(MAX_FORWARD_SEQUENCE_DISTANCE)
        {
            return;
        }

        // Never evict expected-near data to admit a farther packet. Both count and
        // byte ceilings reject the new/future packet instead.
        let packet_bytes = audio_payload_len(&packet);
        let replaced_bytes = self
            .buffer
            .get(&packet.sequence_number)
            .map_or(0, audio_payload_len);
        if (self.buffer.len() >= MAX_BUFFERED_PACKETS
            && !self.buffer.contains_key(&packet.sequence_number))
            || self
                .payload_bytes
                .saturating_sub(replaced_bytes)
                .saturating_add(packet_bytes)
                > MAX_BUFFERED_PAYLOAD_BYTES
        {
            return;
        }

        if let Some(old) = self.buffer.insert(packet.sequence_number, packet) {
            self.payload_bytes = self.payload_bytes.saturating_sub(audio_payload_len(&old));
        }
        self.payload_bytes += packet_bytes;
    }

    pub fn pop(&mut self) -> Option<AudioPacketMessageOrdered> {
        if !self.initialized {
            return None;
        }

        if !self.prebuffered {
            if self.buffer.len() < 15 {
                return None;
            }
            self.prebuffered = true;
        }

        let seq_num = self.expected_sequence_number;

        if let Some(packet) = self.buffer.remove(&seq_num) {
            self.payload_bytes = self
                .payload_bytes
                .saturating_sub(audio_payload_len(&packet));
            self.remember_played_packet(&packet);
            self.played_packets.insert(seq_num);
            self.cleanup_played_packets(seq_num);
            if seq_num == i32::MAX {
                // No valid successor exists. Drop this session's remaining state and wait
                // for a new session instead of repeatedly playing the saturated MAX packet.
                self.reset();
            } else {
                self.expected_sequence_number = seq_num + 1;
            }
            return Some(packet);
        }

        let highest_seq = self.buffer.keys().next_back().copied();
        if let Some(highest) = highest_seq {
            if highest >= seq_num.saturating_add(5) {
                // Gap confirmed. Try FEC recovery.
                if let Some(recovered) = self.try_fec_recovery(seq_num) {
                    self.expected_sequence_number = self.expected_sequence_number.saturating_add(1);
                    return Some(recovered);
                } else {
                    // Cannot recover, skip this seq
                    self.expected_sequence_number = self.expected_sequence_number.saturating_add(1);
                }
            }
        }

        None
    }

    fn remember_played_packet(&mut self, packet: &AudioPacketMessageOrdered) {
        if packet.audio_packet.is_none() {
            return;
        }
        self.played_audio_packets
            .insert(packet.sequence_number, packet.clone());

        // Group boundaries come from parity packets rather than sequence arithmetic:
        // old Android groups after the first start at 13, 26, ... while new groups
        // remain contiguous at 0, 12, 24, ....
        let matching_groups: Vec<i32> = self
            .fec_packets
            .keys()
            .copied()
            .filter(|start| self.sequence_in_fec_group(packet.sequence_number, *start))
            .collect();
        for group_start in matching_groups {
            self.add_played_to_group(group_start, packet);
        }
        self.trim_played_history();
    }

    fn sequence_in_fec_group(&self, sequence: i32, group_start: i32) -> bool {
        sequence >= group_start
            && sequence < group_start.saturating_add(self.fec_group_size)
    }

    fn add_played_to_group(&mut self, group_start: i32, packet: &AudioPacketMessageOrdered) {
        let Some(audio) = packet.audio_packet.as_ref() else {
            return;
        };
        let group = self
            .played_fec_groups
            .entry(group_start)
            .or_insert_with(|| PlayedFecGroup {
                xor_buffer: vec![0; audio.buffer.len()],
                sequences: HashSet::new(),
                reference: PlayedPacketReference {
                    timestamp: packet.timestamp,
                    session_id: packet.session_id,
                    sample_rate: audio.sample_rate,
                    channel_count: audio.channel_count,
                    audio_format: audio.audio_format,
                },
            });
        if !group.sequences.insert(packet.sequence_number) {
            return;
        }
        if group.xor_buffer.len() < audio.buffer.len() {
            group.xor_buffer.resize(audio.buffer.len(), 0);
        }
        for (dst, src) in group.xor_buffer.iter_mut().zip(&audio.buffer) {
            *dst ^= *src;
        }
    }

    fn played_history_payload_bytes(&self) -> usize {
        self.played_audio_packets
            .values()
            .map(audio_payload_len)
            .chain(
                self.played_fec_groups
                    .values()
                    .map(|group| group.xor_buffer.len()),
            )
            .fold(0, usize::saturating_add)
    }

    fn trim_played_history(&mut self) {
        while self.played_fec_groups.len() > MAX_PLAYED_FEC_GROUPS {
            let Some(oldest) = self.played_fec_groups.keys().next().copied() else {
                break;
            };
            self.played_fec_groups.remove(&oldest);
        }
        while self.played_audio_packets.len() > MAX_PLAYED_PACKETS
            || self.played_history_payload_bytes() > MAX_BUFFERED_PAYLOAD_BYTES
        {
            let Some(oldest) = self.played_audio_packets.keys().next().copied() else {
                break;
            };
            self.played_audio_packets.remove(&oldest);
            self.played_packets.remove(&oldest);
        }
    }

    fn try_fec_recovery(&mut self, missing_seq: i32) -> Option<AudioPacketMessageOrdered> {
        let group_start = self
            .fec_packets
            .keys()
            .copied()
            .find(|start| self.sequence_in_fec_group(missing_seq, *start))?;
        let group_end = group_start.saturating_add(self.fec_group_size);
        if group_end <= group_start {
            return None;
        }
        let fec_packet = self.fec_packets.get(&group_start)?;
        let mut recovered_buffer = fec_packet.audio_packet.as_ref()?.buffer.clone();
        let mut received = 0;
        let mut reference = None;

        if let Some(played) = self.played_fec_groups.get(&group_start) {
            for (dst, src) in recovered_buffer.iter_mut().zip(&played.xor_buffer) {
                *dst ^= *src;
            }
            received += played.sequences.len();
            reference = Some(PlayedPacketReference {
                timestamp: played.reference.timestamp,
                session_id: played.reference.session_id,
                sample_rate: played.reference.sample_rate,
                channel_count: played.reference.channel_count,
                audio_format: played.reference.audio_format,
            });
        }
        for seq in group_start..group_end {
            let summarized = self
                .played_fec_groups
                .get(&group_start)
                .is_some_and(|played| played.sequences.contains(&seq));
            if seq == missing_seq || summarized {
                continue;
            }
            let packet = self.buffer.get(&seq)?;
            let audio = packet.audio_packet.as_ref()?;
            for (dst, src) in recovered_buffer.iter_mut().zip(&audio.buffer) {
                *dst ^= *src;
            }
            received += 1;
            reference.get_or_insert_with(|| PlayedPacketReference {
                timestamp: packet.timestamp,
                session_id: packet.session_id,
                sample_rate: audio.sample_rate,
                channel_count: audio.channel_count,
                audio_format: audio.audio_format,
            });
        }
        if received != self.fec_group_size.saturating_sub(1) as usize {
            return None;
        }
        let reference = reference?;
        Some(AudioPacketMessageOrdered {
            sequence_number: missing_seq,
            fec_sequence_number: -1,
            timestamp: reference.timestamp,
            fec_buffer: Vec::new(),
            session_id: reference.session_id,
            audio_packet: Some(AudioPacketMessage {
                buffer: recovered_buffer,
                sample_rate: reference.sample_rate,
                channel_count: reference.channel_count,
                audio_format: reference.audio_format,
            }),
        })
    }

    fn cleanup_played_packets(&mut self, current_seq: i32) {
        let threshold = current_seq.saturating_sub(self.fec_group_size.saturating_mul(2));
        self.played_packets.retain(|seq| *seq >= threshold);
        self.played_audio_packets
            .retain(|seq, _| *seq >= threshold);
        self.played_fec_groups
            .retain(|group, _| group.saturating_add(self.fec_group_size) >= threshold);
        self.trim_played_history();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use micyou_protocol::micyou::AudioPacketMessage;

    fn packet(sequence_number: i32, session_id: i64) -> AudioPacketMessageOrdered {
        AudioPacketMessageOrdered {
            sequence_number,
            audio_packet: Some(AudioPacketMessage {
                buffer: vec![sequence_number as u8],
                sample_rate: 48_000,
                channel_count: 1,
                audio_format: 2,
            }),
            timestamp: 0,
            fec_buffer: Vec::new(),
            fec_sequence_number: -1,
            session_id,
        }
    }

    fn fec_packet(
        packet_sequence: i32,
        group_start: i32,
        values: std::ops::Range<i32>,
        session_id: i64,
    ) -> AudioPacketMessageOrdered {
        let mut fec = packet(packet_sequence, session_id);
        fec.fec_sequence_number = group_start;
        fec.fec_buffer = vec![1];
        fec.audio_packet.as_mut().unwrap().buffer =
            vec![values.fold(0_u8, |parity, value| parity ^ value as u8)];
        fec
    }

    #[test]
    fn new_session_restarts_from_zero_and_prebuffers() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
        assert_eq!(jitter.pop().unwrap().sequence_number, 1);

        jitter.prepare_transport_session(ExpectedAudioSession::Bound(202));
        for sequence in 0..15 {
            jitter.push(packet(sequence, 202));
        }

        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
        assert_eq!(jitter.current_session_id, 202);
        assert!(jitter.prebuffered);
    }

    #[test]
    fn switching_session_clears_buffered_packets() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(202));
        jitter.push(packet(0, 202));

        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.buffer.contains_key(&0));
        assert!(jitter.fec_packets.is_empty());
        assert!(jitter.played_packets.is_empty());
        assert!(!jitter.prebuffered);
        assert!(jitter.pop().is_none());
    }

    #[test]
    fn lower_session_id_can_replace_higher_session_id() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(0, 202));
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));

        assert_eq!(jitter.current_session_id, 101);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.retired_session_ids.contains(&202));
    }

    #[test]
    fn delayed_packet_from_old_session_cannot_replace_active_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(0, 202));
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));
        jitter.push(packet(99, 202));

        assert_eq!(jitter.current_session_id, 101);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.buffer.contains_key(&0));
        assert!(!jitter.buffer.contains_key(&99));
    }

    #[test]
    fn retired_session_cache_is_bounded() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for session_id in 1..=34 {
            jitter.prepare_transport_session(ExpectedAudioSession::Bound(session_id));
            jitter.push(packet(0, session_id));
        }

        assert_eq!(jitter.current_session_id, 34);
        assert_eq!(jitter.retired_session_ids.len(), MAX_RETIRED_SESSION_IDS);
        assert!(!jitter.retired_session_ids.contains(&1));
        assert!(jitter.retired_session_ids.contains(&2));
        assert!(jitter.retired_session_ids.contains(&33));
    }

    #[test]
    fn legacy_packets_are_rejected_after_non_zero_session_starts() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));
        jitter.push(packet(99, 0));

        assert_eq!(jitter.current_session_id, 101);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(!jitter.buffer.contains_key(&99));
    }

    #[test]
    fn pending_legacy_session_rejects_old_modern_packets() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(99, 101));

        assert_eq!(jitter.current_session_id, 0);
        assert!(jitter.expected_session == ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.retired_session_ids.contains(&101));
        assert!(jitter.buffer.is_empty());
    }

    #[test]
    fn old_modern_packet_does_not_block_pending_legacy_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(99, 101));
        jitter.push(packet(0, 0));

        assert_eq!(jitter.current_session_id, 0);
        assert!(jitter.expected_session != ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.retired_session_ids.contains(&101));
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.buffer.contains_key(&0));
    }

    #[test]
    fn expected_modern_session_rejects_old_packets_until_matching_first_packet() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        assert_eq!(jitter.pop().unwrap().session_id, 101);

        jitter.prepare_transport_session(ExpectedAudioSession::Bound(202));
        assert!(jitter.pop().is_none());
        assert!(jitter.buffer.is_empty());
        jitter.push(packet(99, 101));
        assert!(jitter.pop().is_none());
        assert!(jitter.buffer.is_empty());

        jitter.push(packet(7, 202));
        assert_eq!(jitter.current_session_id, 202);
        assert!(jitter.expected_session != ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.buffer.contains_key(&7));
        assert_eq!(jitter.expected_sequence_number, 7);
    }

    #[test]
    fn pending_legacy_session_rejects_old_high_sequence_before_new_first_packet() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(0));
        for sequence in 100..115 {
            jitter.push(packet(sequence, 0));
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, 100);

        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(115, 0));
        assert!(jitter.expected_session == ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.buffer.is_empty());
        assert!(jitter.pop().is_none());

        jitter.push(packet(0, 0));
        assert!(jitter.expected_session != ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.buffer.contains_key(&0));
    }

    #[test]
    fn pending_transport_session_allows_legacy_packets_after_modern_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);

        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 0));
        }

        assert_eq!(jitter.current_session_id, 0);
        assert!(jitter.expected_session != ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.retired_session_ids.contains(&101));
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
    }

    #[test]
    fn delayed_modern_packet_is_rejected_after_legacy_session_begins() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(0, 0));
        jitter.push(packet(99, 101));

        assert_eq!(jitter.current_session_id, 0);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.buffer.contains_key(&0));
        assert!(!jitter.buffer.contains_key(&99));
    }

    #[test]
    fn many_future_packets_keep_regular_buffer_bounded() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));
        for sequence in 1..10_000 {
            jitter.push(packet(sequence, 101));
        }

        assert_eq!(jitter.buffer.len(), MAX_BUFFERED_PACKETS);
        assert!(jitter.buffer.contains_key(&0));
        assert!(jitter.buffer.contains_key(&MAX_FORWARD_SEQUENCE_DISTANCE));
    }

    #[test]
    fn excessively_future_packet_does_not_displace_expected_packet() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(100, 101));
        jitter.push(packet(i32::MAX, 101));
        for sequence in 101..115 {
            jitter.push(packet(sequence, 101));
        }

        assert!(!jitter.buffer.contains_key(&i32::MAX));
        assert_eq!(jitter.pop().unwrap().sequence_number, 100);
    }

    #[test]
    fn normal_prebuffer_and_fec_recovery_are_preserved() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        let missing = 17;
        for sequence in 12..28 {
            if sequence != missing {
                jitter.push(packet(sequence, 101));
            }
        }

        let parity = (12..24).fold(0_u8, |value, sequence| value ^ sequence as u8);
        let mut fec = packet(24, 101);
        fec.fec_sequence_number = 12;
        fec.fec_buffer = vec![1];
        fec.audio_packet.as_mut().unwrap().buffer = vec![parity];
        jitter.push(fec);

        for expected in 12..17 {
            assert_eq!(jitter.pop().unwrap().sequence_number, expected);
        }
        let recovered = jitter.pop().unwrap();
        assert_eq!(recovered.sequence_number, missing);
        assert_eq!(recovered.audio_packet.unwrap().buffer, vec![missing as u8]);
    }

    #[test]
    fn two_new_android_groups_keep_regular_sequence_contiguous_and_recover_second_group() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        let missing = 17;
        for sequence in 0..30 {
            if sequence != missing {
                jitter.push(packet(sequence, 101));
            }
            if sequence == 11 {
                jitter.push(fec_packet(12, 0, 0..12, 101));
            } else if sequence == 23 {
                jitter.push(fec_packet(24, 12, 12..24, 101));
            }
        }

        assert_eq!(jitter.buffer.len(), 29);
        for expected in 0..missing {
            assert_eq!(jitter.pop().unwrap().sequence_number, expected);
        }
        let recovered = jitter.pop().unwrap();
        assert_eq!(recovered.sequence_number, missing);
        assert_eq!(recovered.audio_packet.unwrap().buffer, vec![missing as u8]);
        assert_eq!(jitter.expected_sequence_number, missing + 1);
    }

    #[test]
    fn explicit_group_boundary_recovers_after_first_half_was_popped() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        let missing = 8;
        for sequence in 0..20 {
            if sequence != missing {
                jitter.push(packet(sequence, 101));
            }
        }
        for expected in 0..6 {
            assert_eq!(jitter.pop().unwrap().sequence_number, expected);
        }
        jitter.push(fec_packet(12, 0, 0..12, 101));
        assert_eq!(jitter.pop().unwrap().sequence_number, 6);
        assert_eq!(jitter.pop().unwrap().sequence_number, 7);
        assert_eq!(jitter.pop().unwrap().sequence_number, missing);
    }

    #[test]
    fn old_android_layout_group_start_thirteen_is_recovered() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        let missing = 17;
        for sequence in 0..30 {
            if sequence != missing && sequence != 12 && sequence != 25 {
                jitter.push(packet(sequence, 101));
            }
        }
        // Old Android consumed sequence 12 for first parity, so its second regular
        // group is explicitly 13..24 and parity consumes sequence 25.
        let mut fec = fec_packet(25, 13, 13..25, 101);
        fec.fec_buffer.clear();
        jitter.push(fec);

        for expected in 0..12 {
            assert_eq!(jitter.pop().unwrap().sequence_number, expected);
        }
        // Sequence 12 was consumed by old parity and is not regular audio.
        jitter.expected_sequence_number = 13;
        for expected in 13..missing {
            assert_eq!(jitter.pop().unwrap().sequence_number, expected);
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, missing);
    }

    #[test]
    fn fec_packet_does_not_enter_regular_buffer_or_advance_expected_sequence() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
        let regular_len = jitter.buffer.len();

        jitter.push(fec_packet(12, 0, 0..12, 101));

        assert_eq!(jitter.expected_sequence_number, 1);
        assert_eq!(jitter.buffer.len(), regular_len);
        assert!(jitter.buffer.contains_key(&12));
        assert!(jitter.fec_packets.contains_key(&0));
    }

    #[test]
    fn explicit_marker_layout_keeps_wire_default_sequence_twelve_regular() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        let mut regular = packet(12, 101);
        // Kotlin omits its -1 default; proto3 decodes the missing scalar as zero.
        regular.fec_sequence_number = 0;
        jitter.push(regular);

        assert!(jitter.buffer.contains_key(&12));
        assert!(jitter.fec_packets.is_empty());
    }

    #[test]
    fn explicit_marker_layout_recognizes_non_empty_fec_marker() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(fec_packet(12, 0, 0..12, 101));

        assert!(!jitter.buffer.contains_key(&12));
        assert!(jitter.fec_packets.contains_key(&0));
    }

    #[test]
    fn legacy_first_group_zero_parity_is_recognized_but_regular_zero_is_not() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(0, 101));
        assert!(jitter.buffer.contains_key(&0));
        assert!(jitter.fec_packets.is_empty());

        let mut old_fec = fec_packet(12, 0, 0..12, 101);
        old_fec.fec_buffer.clear();
        jitter.push(old_fec);

        assert!(jitter.buffer.contains_key(&0));
        assert!(!jitter.buffer.contains_key(&12));
        assert!(jitter.fec_packets.contains_key(&0));
        assert_eq!(jitter.expected_sequence_number, 0);
    }

    #[test]
    fn switching_sessions_does_not_inherit_legacy_fec_layout() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session(ExpectedAudioSession::Bound(202));
        let mut regular = packet(12, 202);
        regular.fec_sequence_number = 0;
        jitter.push(regular);

        assert_eq!(jitter.fec_layout, FecLayout::ExplicitMarker);
        assert!(jitter.buffer.contains_key(&12));
        assert!(jitter.fec_packets.is_empty());
    }

    #[test]
    fn overflowing_fec_group_boundary_is_safe() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(i32::MAX - 2, 101));
        jitter.prebuffered = true;
        jitter.push(fec_packet(i32::MAX, i32::MAX - 2, 0..12, 101));

        assert!(jitter.fec_packets.contains_key(&(i32::MAX - 2)));
        assert!(!jitter.buffer.contains_key(&i32::MAX));
        assert!(jitter.try_fec_recovery(i32::MAX - 1).is_none());
        assert_eq!(jitter.expected_sequence_number, i32::MAX - 2);
    }

    #[test]
    fn pure_legacy_packets_keep_existing_behavior() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 0));
        }

        assert_eq!(jitter.current_session_id, 0);
        assert!(jitter.retired_session_ids.is_empty());
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
    }

    #[test]
    fn old_epoch_packet_cannot_bind_new_legacy_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session_epoch(ExpectedAudioSession::UnboundLegacy, 10);
        jitter.push_epoch(packet(0, 101), 9);
        assert!(jitter.buffer.is_empty());
        assert_eq!(jitter.expected_session, ExpectedAudioSession::UnboundLegacy);

        jitter.push_epoch(packet(0, 202), 10);
        assert!(jitter.buffer.contains_key(&0));
        assert_eq!(jitter.expected_session, ExpectedAudioSession::Bound(202));
    }

    #[test]
    fn payload_budget_includes_regular_and_fec_packets() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        for sequence in 0..MAX_BUFFERED_PACKETS as i32 {
            let mut large = packet(sequence, 101);
            large.audio_packet.as_mut().unwrap().buffer = vec![0; 64 * 1024];
            jitter.push(large);
        }
        assert!(jitter.payload_bytes <= MAX_BUFFERED_PAYLOAD_BYTES);
        assert!(jitter.buffer.len() < MAX_BUFFERED_PACKETS);
        let before = jitter.payload_bytes;
        let mut fec = packet(72, 101);
        fec.fec_sequence_number = 60;
        fec.fec_buffer = vec![1];
        fec.audio_packet.as_mut().unwrap().buffer = vec![0; 64 * 1024];
        jitter.push(fec);
        assert!(jitter.payload_bytes <= MAX_BUFFERED_PAYLOAD_BYTES);
        assert!(jitter.payload_bytes >= before);
    }

    #[test]
    fn inactive_rejects_packets() {
        let mut jitter = JitterBuffer::new(12);
        jitter.push(packet(0, 101));

        assert_eq!(jitter.expected_session, ExpectedAudioSession::Inactive);
        assert!(!jitter.initialized);
        assert!(jitter.buffer.is_empty());
    }

    #[test]
    fn unbound_legacy_accepts_non_zero_low_first_packet_and_rejects_other_id() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(0, 202));
        jitter.push(packet(1, 101));

        assert_eq!(jitter.expected_session, ExpectedAudioSession::Bound(202));
        assert_eq!(jitter.current_session_id, 202);
        assert_eq!(jitter.buffer.len(), 1);
    }

    #[test]
    fn unbound_legacy_drops_fec_before_regular_first_packet() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        let mut fec = packet(0, 202);
        fec.fec_sequence_number = 12;
        jitter.push(fec);

        assert_eq!(jitter.expected_session, ExpectedAudioSession::UnboundLegacy);
        assert!(jitter.fec_packets.is_empty());
        assert!(jitter.buffer.is_empty());

        jitter.push(packet(0, 202));
        assert_eq!(jitter.expected_session, ExpectedAudioSession::Bound(202));
    }

    #[test]
    fn negative_sequence_is_rejected_without_changing_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::UnboundLegacy);
        jitter.push(packet(-1, 101));

        assert!(!jitter.initialized);
        assert_eq!(jitter.current_session_id, 0);
        assert!(jitter.buffer.is_empty());
    }

    #[test]
    fn maximum_sequence_is_played_once_then_waits_for_new_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(i32::MAX, 101));
        jitter.prebuffered = true;

        assert_eq!(jitter.pop().unwrap().sequence_number, i32::MAX);
        assert!(jitter.pop().is_none());
        assert!(!jitter.initialized);
    }

    #[test]
    fn played_packet_history_has_hard_entry_limit() {
        let mut jitter = JitterBuffer::new(1000);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.push(packet(0, 101));
        jitter.prebuffered = true;
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
        for sequence in 1..100 {
            jitter.push(packet(sequence, 101));
            assert_eq!(jitter.pop().unwrap().sequence_number, sequence);
        }

        assert_eq!(jitter.played_packets.len(), MAX_PLAYED_PACKETS);
        assert!(!jitter.played_packets.contains(&0));
        assert!(jitter.played_packets.contains(&99));
    }

    #[test]
    fn played_audio_payload_and_fec_summaries_are_strictly_bounded() {
        let mut jitter = JitterBuffer::new(1000);
        jitter.prepare_transport_session(ExpectedAudioSession::Bound(101));
        jitter.prebuffered = true;
        for sequence in 0..MAX_PLAYED_PACKETS as i32 {
            let mut large = packet(sequence, 101);
            large.audio_packet.as_mut().unwrap().buffer = vec![sequence as u8; 64 * 1024];
            jitter.push(large);
            assert_eq!(jitter.pop().unwrap().sequence_number, sequence);
        }
        for group_start in [0, 12, 24] {
            jitter.push(fec_packet(
                group_start + 12,
                group_start,
                group_start..group_start + 12,
                101,
            ));
        }

        assert!(jitter.played_history_payload_bytes() <= MAX_BUFFERED_PAYLOAD_BYTES);
        assert!(jitter.played_audio_packets.len() <= MAX_PLAYED_PACKETS);
        assert!(jitter.played_fec_groups.len() <= MAX_PLAYED_FEC_GROUPS);
        assert!(!jitter.played_fec_groups.contains_key(&0));
    }
}
