use std::collections::BTreeMap;
use micyou_protocol::micyou::AudioPacketMessageOrdered;

pub struct JitterBuffer {
    buffer: BTreeMap<i32, AudioPacketMessageOrdered>,
    fec_packets: BTreeMap<i32, AudioPacketMessageOrdered>,
    played_packets: BTreeMap<i32, AudioPacketMessageOrdered>,
    
    expected_sequence_number: i32,
    initialized: bool,
    fec_group_size: i32,
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
        }
    }

    pub fn push(&mut self, packet: AudioPacketMessageOrdered) {
        // Due to proto3 defaulting missing ints to 0, and Kotlin sending -1 which gets omitted,
        // regular packets will have fec_sequence_number == 0.
        // FEC packets have fec_sequence_number = fecGroupStartSeq (0, 12, 24...)
        let is_fec = packet.fec_sequence_number > 0 || 
                    (packet.fec_sequence_number == 0 && packet.sequence_number == self.fec_group_size);

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

        let seq_num = self.expected_sequence_number;

        if let Some(packet) = self.buffer.remove(&seq_num) {
            self.played_packets.insert(seq_num, packet.clone());
            self.cleanup_played_packets(seq_num);
            self.expected_sequence_number += 1;
            return Some(packet);
        }

        let next_available = self.buffer.keys().next().copied();
        if let Some(next_seq) = next_available {
            if next_seq <= seq_num {
                return None;
            }

            // Gap detected! Try FEC recovery.
            if let Some(recovered) = self.try_fec_recovery(seq_num) {
                self.expected_sequence_number += 1;
                return Some(recovered);
            } else {
                // Cannot recover, we must skip to next available
                self.expected_sequence_number = next_seq;
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
            if let Some(pkt) = self.buffer.get(&seq).or_else(|| self.played_packets.get(&seq)) {
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

        let reference_packet = self.buffer.get(&group_start)
            .or_else(|| self.played_packets.get(&group_start))
            .or_else(|| self.buffer.get(&(group_start + 1)))
            .or_else(|| self.played_packets.get(&(group_start + 1)))?;

        let ref_audio = reference_packet.audio_packet.as_ref()?;

        let recovered = AudioPacketMessageOrdered {
            sequence_number: missing_seq,
            fec_sequence_number: -1,
            timestamp: reference_packet.timestamp,
            fec_buffer: Vec::new(),
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
        
        let keys_to_remove: Vec<i32> = self.played_packets.keys()
            .filter(|&&k| k < threshold)
            .copied()
            .collect();
            
        for k in keys_to_remove {
            self.played_packets.remove(&k);
        }
    }
}
