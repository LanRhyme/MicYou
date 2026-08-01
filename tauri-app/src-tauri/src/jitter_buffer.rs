use micyou_protocol::micyou::AudioPacketMessageOrdered;
use std::collections::{BTreeMap, VecDeque};

const MAX_RETIRED_SESSION_IDS: usize = 32;

pub struct JitterBuffer {
    buffer: BTreeMap<i32, AudioPacketMessageOrdered>,
    fec_packets: BTreeMap<i32, AudioPacketMessageOrdered>,
    played_packets: BTreeMap<i32, AudioPacketMessageOrdered>,

    expected_sequence_number: i32,
    initialized: bool,
    fec_group_size: i32,
    prebuffered: bool,
    current_session_id: i64,
    retired_session_ids: VecDeque<i64>,
    transport_session_pending: bool,
}

impl JitterBuffer {
    pub fn new(fec_group_size: i32) -> Self {
        Self {
            buffer: BTreeMap::new(),
            fec_packets: BTreeMap::new(),
            played_packets: BTreeMap::new(),
            expected_sequence_number: 0,
            initialized: false,
            fec_group_size,
            prebuffered: false,
            current_session_id: 0,
            retired_session_ids: VecDeque::with_capacity(MAX_RETIRED_SESSION_IDS),
            transport_session_pending: false,
        }
    }

    fn reset(&mut self) {
        self.buffer.clear();
        self.fec_packets.clear();
        self.played_packets.clear();
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

    pub fn prepare_transport_session(&mut self) {
        self.transport_session_pending = true;
    }

    fn begin_transport_session(&mut self) {
        self.retire_session(self.current_session_id);
        self.reset();
        self.current_session_id = 0;
        self.transport_session_pending = false;
    }

    pub fn push(&mut self, packet: AudioPacketMessageOrdered) {
        let packet_session_id = packet.session_id;
        if self.transport_session_pending {
            if packet_session_id == 0 {
                self.begin_transport_session();
            } else if packet_session_id != self.current_session_id {
                self.transport_session_pending = false;
            }
        }

        if packet_session_id == 0 {
            // Legacy clients do not send a session ID and therefore still have no protocol-level
            // session isolation. Once a non-zero session is active, reject legacy packets so they
            // cannot contaminate it.
            if self.current_session_id != 0 {
                return;
            }
        } else if packet_session_id != self.current_session_id {
            if self.retired_session_ids.contains(&packet_session_id) {
                return;
            }

            self.retire_session(self.current_session_id);
            self.reset();
            self.current_session_id = packet_session_id;
            self.transport_session_pending = false;
        }

        // Due to proto3 defaulting missing ints to 0, and Kotlin sending -1 which gets omitted,
        // regular packets will have fec_sequence_number == 0.
        // FEC packets have fec_sequence_number = fecGroupStartSeq (0, 12, 24...)
        let is_fec = packet.fec_sequence_number > 0
            || (packet.fec_sequence_number == 0 && packet.sequence_number == self.fec_group_size);

        if is_fec {
            self.fec_packets.insert(packet.fec_sequence_number, packet);
            // Cleanup old FEC packets
            while self.fec_packets.len() > 10 {
                if let Some(key) = self.fec_packets.keys().next().cloned() {
                    self.fec_packets.remove(&key);
                }
            }
            return;
        }

        if !self.initialized {
            self.expected_sequence_number = packet.sequence_number;
            self.initialized = true;
        }

        if packet.sequence_number < self.expected_sequence_number {
            return;
        }

        self.buffer.insert(packet.sequence_number, packet);
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
            self.played_packets.insert(seq_num, packet.clone());
            self.cleanup_played_packets(seq_num);
            self.expected_sequence_number += 1;
            return Some(packet);
        }

        let highest_seq = self.buffer.keys().next_back().copied();
        if let Some(highest) = highest_seq {
            if highest >= seq_num + 5 {
                // Gap confirmed. Try FEC recovery.
                if let Some(recovered) = self.try_fec_recovery(seq_num) {
                    self.expected_sequence_number += 1;
                    return Some(recovered);
                } else {
                    // Cannot recover, skip this seq
                    self.expected_sequence_number += 1;
                }
            }
        }

        None
    }

    fn try_fec_recovery(&mut self, missing_seq: i32) -> Option<AudioPacketMessageOrdered> {
        let group_start = (missing_seq / self.fec_group_size) * self.fec_group_size;
        let fec_packet = self.fec_packets.get(&group_start)?;

        let mut received_in_group = Vec::new();
        for seq in group_start..(group_start + self.fec_group_size) {
            if seq == missing_seq {
                continue;
            }
            if let Some(pkt) = self
                .buffer
                .get(&seq)
                .or_else(|| self.played_packets.get(&seq))
            {
                if let Some(audio_pkt) = &pkt.audio_packet {
                    received_in_group.push(&audio_pkt.buffer);
                } else {
                    return None;
                }
            } else {
                return None; // Cannot recover if more than 1 packet missing
            }
        }

        if received_in_group.len() != (self.fec_group_size - 1) as usize {
            return None;
        }

        let mut recovered_buffer = fec_packet.audio_packet.as_ref()?.buffer.clone();
        for buf in received_in_group {
            for i in 0..recovered_buffer.len().min(buf.len()) {
                recovered_buffer[i] ^= buf[i];
            }
        }

        let reference_packet = self
            .buffer
            .get(&group_start)
            .or_else(|| self.played_packets.get(&group_start))
            .or_else(|| self.buffer.get(&(group_start + 1)))
            .or_else(|| self.played_packets.get(&(group_start + 1)))?;

        let ref_audio = reference_packet.audio_packet.as_ref()?;

        let recovered = AudioPacketMessageOrdered {
            sequence_number: missing_seq,
            fec_sequence_number: -1,
            timestamp: reference_packet.timestamp,
            fec_buffer: Vec::new(),
            session_id: reference_packet.session_id,
            audio_packet: Some(micyou_protocol::micyou::AudioPacketMessage {
                buffer: recovered_buffer,
                sample_rate: ref_audio.sample_rate,
                channel_count: ref_audio.channel_count,
                audio_format: ref_audio.audio_format,
            }),
        };

        Some(recovered)
    }

    fn cleanup_played_packets(&mut self, current_seq: i32) {
        let threshold = current_seq - self.fec_group_size * 2;
        if threshold <= 0 {
            return;
        }

        self.played_packets = self.played_packets.split_off(&threshold);
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

    #[test]
    fn new_session_restarts_from_zero_and_prebuffers() {
        let mut jitter = JitterBuffer::new(12);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
        assert_eq!(jitter.pop().unwrap().sequence_number, 1);

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
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
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
        jitter.push(packet(0, 202));
        jitter.push(packet(0, 101));

        assert_eq!(jitter.current_session_id, 101);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.retired_session_ids.contains(&202));
    }

    #[test]
    fn delayed_packet_from_old_session_cannot_replace_active_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.push(packet(0, 202));
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
        for session_id in 1..=34 {
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
        jitter.push(packet(0, 101));
        jitter.push(packet(99, 0));

        assert_eq!(jitter.current_session_id, 101);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(!jitter.buffer.contains_key(&99));
    }

    #[test]
    fn pending_transport_session_keeps_active_modern_session_for_same_id() {
        let mut jitter = JitterBuffer::new(12);
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session();
        jitter.push(packet(1, 101));

        assert_eq!(jitter.current_session_id, 101);
        assert!(jitter.transport_session_pending);
        assert!(!jitter.retired_session_ids.contains(&101));
        assert_eq!(jitter.buffer.len(), 2);
    }

    #[test]
    fn old_modern_packet_does_not_block_pending_legacy_session() {
        let mut jitter = JitterBuffer::new(12);
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session();
        jitter.push(packet(1, 101));
        jitter.push(packet(0, 0));

        assert_eq!(jitter.current_session_id, 0);
        assert!(!jitter.transport_session_pending);
        assert!(jitter.retired_session_ids.contains(&101));
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.buffer.contains_key(&0));
    }

    #[test]
    fn pending_transport_session_switches_to_new_modern_id() {
        let mut jitter = JitterBuffer::new(12);
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session();
        jitter.push(packet(1, 101));
        assert!(jitter.transport_session_pending);
        jitter.push(packet(0, 202));

        assert_eq!(jitter.current_session_id, 202);
        assert!(!jitter.transport_session_pending);
        assert!(jitter.retired_session_ids.contains(&101));
        assert_eq!(jitter.buffer.len(), 1);
    }

    #[test]
    fn pending_transport_session_allows_legacy_packets_after_modern_session() {
        let mut jitter = JitterBuffer::new(12);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 101));
        }
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);

        jitter.prepare_transport_session();
        for sequence in 0..15 {
            jitter.push(packet(sequence, 0));
        }

        assert_eq!(jitter.current_session_id, 0);
        assert!(!jitter.transport_session_pending);
        assert!(jitter.retired_session_ids.contains(&101));
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
    }

    #[test]
    fn delayed_modern_packet_is_rejected_after_legacy_session_begins() {
        let mut jitter = JitterBuffer::new(12);
        jitter.push(packet(0, 101));

        jitter.prepare_transport_session();
        jitter.push(packet(0, 0));
        jitter.push(packet(99, 101));

        assert_eq!(jitter.current_session_id, 0);
        assert_eq!(jitter.buffer.len(), 1);
        assert!(jitter.buffer.contains_key(&0));
        assert!(!jitter.buffer.contains_key(&99));
    }

    #[test]
    fn pure_legacy_packets_keep_existing_behavior() {
        let mut jitter = JitterBuffer::new(12);
        for sequence in 0..15 {
            jitter.push(packet(sequence, 0));
        }

        assert_eq!(jitter.current_session_id, 0);
        assert!(jitter.retired_session_ids.is_empty());
        assert_eq!(jitter.pop().unwrap().sequence_number, 0);
    }
}
