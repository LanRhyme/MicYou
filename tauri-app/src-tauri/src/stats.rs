use serde::Serialize;
use std::sync::atomic::{AtomicI64, AtomicU32, AtomicU64, Ordering};

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct AudioMetrics {
    pub bitrate: i32,
    pub sample_rate: i32,
    pub latency_ms: i64,
    pub network_latency_ms: i64,
    pub packet_loss_rate: f64,
    pub jitter_ms: f64,
    pub buffer_duration_ms: i64,
}

pub struct NetworkStats {
    pub rtt_ms: AtomicI64,
    /// Jitter value stored as f64 bits in an AtomicU64.
    pub jitter_bits: AtomicU64,
    pub loss_rate_bits: AtomicU64,
    pub last_udp_packet_time_ms: AtomicU64,
    pub tcp_connected_time_ms: AtomicU64,
    pub bitrate: AtomicU32,
    pub sample_rate: AtomicU32,
}

impl Default for NetworkStats {
    fn default() -> Self {
        Self {
            rtt_ms: AtomicI64::new(0),
            jitter_bits: AtomicU64::new(0f64.to_bits()),
            loss_rate_bits: AtomicU64::new(0f64.to_bits()),
            last_udp_packet_time_ms: AtomicU64::new(0),
            tcp_connected_time_ms: AtomicU64::new(0),
            bitrate: AtomicU32::new(0),
            sample_rate: AtomicU32::new(0),
        }
    }
}

impl NetworkStats {
    pub fn set_rtt(&self, rtt: i64) {
        self.rtt_ms.store(rtt, Ordering::Release);
    }
    pub fn get_rtt(&self) -> i64 {
        self.rtt_ms.load(Ordering::Acquire)
    }

    pub fn set_jitter(&self, jitter: f64) {
        self.jitter_bits.store(jitter.to_bits(), Ordering::Release);
    }
    pub fn get_jitter(&self) -> f64 {
        f64::from_bits(self.jitter_bits.load(Ordering::Acquire))
    }

    pub fn set_loss_rate(&self, loss: f64) {
        self.loss_rate_bits.store(loss.to_bits(), Ordering::Release);
    }
    pub fn get_loss_rate(&self) -> f64 {
        f64::from_bits(self.loss_rate_bits.load(Ordering::Acquire))
    }

    pub fn mark_udp_received(&self, time_ms: u64) {
        self.last_udp_packet_time_ms
            .store(time_ms, Ordering::Release);
    }
    pub fn get_last_udp_time(&self) -> u64 {
        self.last_udp_packet_time_ms.load(Ordering::Acquire)
    }

    pub fn mark_tcp_connected(&self, time_ms: u64) {
        self.tcp_connected_time_ms.store(time_ms, Ordering::Release);
    }
    pub fn get_tcp_connected_time(&self) -> u64 {
        self.tcp_connected_time_ms.load(Ordering::Acquire)
    }

    pub fn set_audio_info(&self, sample_rate: u32, bitrate: u32) {
        self.sample_rate.store(sample_rate, Ordering::Release);
        self.bitrate.store(bitrate, Ordering::Release);
    }

    pub fn to_metrics(&self, buffer_duration: i64) -> AudioMetrics {
        let rtt = self.get_rtt();
        AudioMetrics {
            bitrate: self.bitrate.load(Ordering::Acquire) as i32,
            sample_rate: self.sample_rate.load(Ordering::Acquire) as i32,
            latency_ms: buffer_duration + rtt,
            network_latency_ms: rtt,
            packet_loss_rate: self.get_loss_rate(),
            jitter_ms: self.get_jitter(),
            buffer_duration_ms: buffer_duration,
        }
    }
}
