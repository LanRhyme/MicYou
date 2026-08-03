use tauri_app_lib::events::{AecStatus, ServerEvents};
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

/// Log-mode events: print a compact line per event.
pub struct CliEventSink;

impl ServerEvents for CliEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        println!("[mic] connected: {} ({})", info.name, info.ip);
    }
    fn device_disconnected(&self) {
        println!("[mic] disconnected");
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        println!(
            "[stats] latency {} ms (network {} ms) buffer {} ms jitter {:.1} ms loss {:.2}%",
            metrics.latency_ms,
            metrics.network_latency_ms,
            metrics.buffer_duration_ms,
            metrics.jitter_ms,
            metrics.packet_loss_rate * 100.0
        );
    }
    fn udp_audio_warning(&self) {
        println!("[warn] no UDP audio for a while - check network connection");
    }
    fn mute_state_changed(&self, is_muted: bool) {
        println!("[mic] muted: {is_muted}");
    }
    fn audio_level(&self, level: u32) {
        println!("[level] {level}");
    }
    fn audio_spectrum(&self, _raw: Vec<f32>, _processed: Vec<f32>) {}
    fn server_stopped(&self) {
        println!("[server] stopped");
    }
    fn web_client_count(&self, count: u32) {
        println!("[web] clients: {count}");
    }
    fn install_progress(&self, message: String) {
        println!("[install] {message}");
    }
    fn aec_status_changed(&self, status: AecStatus) {
        if status.available && status.enabled {
            println!("[aec] enabled");
        } else if let Some(reason) = status.reason {
            println!("[warn] AEC disabled: {reason}");
        }
    }
}
