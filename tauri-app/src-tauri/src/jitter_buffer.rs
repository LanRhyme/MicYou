use std::collections::BTreeMap;
use crate::protocol::micyou::AudioPacketMessageOrdered;

pub struct JitterBuffer {
    buffer: BTreeMap<i32, AudioPacketMessageOrdered>,
    last_played_seq: i32,
    buffer_capacity: usize,
}

impl JitterBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            buffer: BTreeMap::new(),
            last_played_seq: -1,
            buffer_capacity: capacity,
        }
    }

    pub fn push(&mut self, packet: AudioPacketMessageOrdered) {
        if self.last_played_seq != -1 && packet.sequence_number <= self.last_played_seq {
            // Drop old packets
            return;
        }
        
        self.buffer.insert(packet.sequence_number, packet);

        // Keep buffer size manageable
        while self.buffer.len() > self.buffer_capacity {
            if let Some(key) = self.buffer.keys().next().cloned() {
                self.buffer.remove(&key);
            }
        }
    }

    pub fn pop(&mut self) -> Option<AudioPacketMessageOrdered> {
        if self.buffer.is_empty() {
            return None;
        }

        // Pop the lowest sequence number
        let key = *self.buffer.keys().next().unwrap();
        self.last_played_seq = key;
        self.buffer.remove(&key)
    }
}
