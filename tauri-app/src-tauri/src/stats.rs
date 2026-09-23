/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

use serde::Serialize;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;

#[derive(Serialize, Clone, Debug)]
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
    pub jitter_ms: AtomicI64,
    pub jitter_bits: AtomicU64,
    pub loss_rate_bits: AtomicU64,
    pub last_udp_packet_time_ms: AtomicU64,
    pub tcp_connected_time_ms: AtomicU64,
    pub bitrate: AtomicU32,
    pub sample_rate: AtomicU32,
    /// Shared hard-mute flag. The audio output engine watches this very
    /// AtomicBool, so flipping the mute state silences the cpal stream (and
    /// drops everything queued) within one device callback period.
    /// Skipped by serde: `Arc<AtomicBool>` is not serializable without the
    /// discouraged "rc" feature, and nothing consumes a serialized
    /// NetworkStats (the frontend receives AudioMetrics via `to_metrics`).
    #[serde(skip)]
    pub is_muted: Arc<AtomicBool>,
    pub channels: AtomicU32,
    pub input_level_bits: AtomicU32,
    pub processed_level_bits: AtomicU32,
}

impl Default for NetworkStats {
    fn default() -> Self {
        Self {
            rtt_ms: AtomicI64::new(0),
            jitter_ms: AtomicI64::new(0),
            jitter_bits: AtomicU64::new(0f64.to_bits()),
            loss_rate_bits: AtomicU64::new(0f64.to_bits()),
            last_udp_packet_time_ms: AtomicU64::new(0),
            tcp_connected_time_ms: AtomicU64::new(0),
            bitrate: AtomicU32::new(0),
            sample_rate: AtomicU32::new(0),
            is_muted: Arc::new(AtomicBool::new(false)),
            channels: AtomicU32::new(0),
            input_level_bits: AtomicU32::new(0f32.to_bits()),
            processed_level_bits: AtomicU32::new(0f32.to_bits()),
        }
    }
}

impl NetworkStats {
    pub fn set_rtt(&self, rtt: i64) {
        let prev = self.rtt_ms.load(Ordering::Relaxed);
        let smoothed = if prev > 0 {
            (prev as f64 * 0.5 + rtt as f64 * 0.5).round() as i64
        } else {
            rtt
        };
        self.rtt_ms.store(smoothed, Ordering::Relaxed);
    }
    pub fn get_rtt(&self) -> i64 {
        self.rtt_ms.load(Ordering::Relaxed)
    }

    pub fn set_jitter(&self, jitter: f64) {
        self.jitter_bits.store(jitter.to_bits(), Ordering::Relaxed);
    }
    pub fn get_jitter(&self) -> f64 {
        f64::from_bits(self.jitter_bits.load(Ordering::Relaxed))
    }

    pub fn set_loss_rate(&self, loss: f64) {
        self.loss_rate_bits.store(loss.to_bits(), Ordering::Relaxed);
    }
    pub fn get_loss_rate(&self) -> f64 {
        f64::from_bits(self.loss_rate_bits.load(Ordering::Relaxed))
    }

    pub fn mark_udp_received(&self, time_ms: u64) {
        self.last_udp_packet_time_ms
            .store(time_ms, Ordering::Relaxed);
    }
    pub fn get_last_udp_time(&self) -> u64 {
        self.last_udp_packet_time_ms.load(Ordering::Relaxed)
    }

    pub fn mark_tcp_connected(&self, time_ms: u64) {
        self.tcp_connected_time_ms.store(time_ms, Ordering::Relaxed);
        self.last_udp_packet_time_ms.store(0, Ordering::Relaxed);
    }
    pub fn mark_tcp_disconnected(&self) {
        self.tcp_connected_time_ms.store(0, Ordering::Relaxed);
        self.last_udp_packet_time_ms.store(0, Ordering::Relaxed);
        self.loss_rate_bits.store(0f64.to_bits(), Ordering::Relaxed);
        self.jitter_bits.store(0f64.to_bits(), Ordering::Relaxed);
        self.rtt_ms.store(0, Ordering::Relaxed);
    }
    pub fn get_tcp_connected_time(&self) -> u64 {
        self.tcp_connected_time_ms.load(Ordering::Relaxed)
    }

    pub fn set_muted(&self, muted: bool) {
        self.is_muted.store(muted, Ordering::Relaxed);
    }
    pub fn is_muted(&self) -> bool {
        self.is_muted.load(Ordering::Relaxed)
    }
    /// Clone of the shared mute flag, for wiring into the audio output engine.
    pub fn mute_flag(&self) -> Arc<AtomicBool> {
        self.is_muted.clone()
    }

    pub fn set_audio_info(&self, sample_rate: u32, bitrate: u32, channels: u32) {
        self.sample_rate.store(sample_rate, Ordering::Relaxed);
        self.bitrate.store(bitrate, Ordering::Relaxed);
        self.channels.store(channels, Ordering::Relaxed);
    }
    
    pub fn set_levels(&self, input: f32, processed: f32) {
        self.input_level_bits.store(input.to_bits(), Ordering::Relaxed);
        self.processed_level_bits.store(processed.to_bits(), Ordering::Relaxed);
    }
    
    pub fn to_metrics(&self, buffer_duration: i64) -> AudioMetrics {
        let rtt = self.get_rtt();
        AudioMetrics {
            bitrate: self.bitrate.load(Ordering::Relaxed) as i32,
            sample_rate: self.sample_rate.load(Ordering::Relaxed) as i32,
            latency_ms: buffer_duration + rtt,
            network_latency_ms: rtt,
            packet_loss_rate: self.get_loss_rate(),
            jitter_ms: self.get_jitter(),
            buffer_duration_ms: buffer_duration,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_network_stats_connect_disconnect_lifecycle() {
        let stats = NetworkStats::default();
        stats.mark_udp_received(12345);
        stats.set_loss_rate(5.5);
        stats.set_jitter(12.0);

        assert_eq!(stats.get_last_udp_time(), 12345);
        assert_eq!(stats.get_loss_rate(), 5.5);

        stats.mark_tcp_connected(20000);
        assert_eq!(stats.get_tcp_connected_time(), 20000);
        assert_eq!(stats.get_last_udp_time(), 0);

        stats.mark_tcp_disconnected();
        assert_eq!(stats.get_tcp_connected_time(), 0);
        assert_eq!(stats.get_last_udp_time(), 0);
        assert_eq!(stats.get_loss_rate(), 0.0);
        assert_eq!(stats.get_jitter(), 0.0);
    }
}
