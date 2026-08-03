use std::sync::mpsc::Sender;
use tauri_app_lib::events::{AecStatus, ServerEvents};
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;
use std::sync::Mutex;
use std::time::{Duration, Instant};

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

/// TUI-mode events: forward to the TUI channel.
/// Level/spectrum events are throttled to ~100ms because the audio thread
/// fires them every ~60ms while the TUI only redraws ~6-7 times per second,
/// so most of them would be dropped anyway - throttling saves the mpsc send,
/// the Vec allocation and the queue churn.
pub struct TuiEventSink {
    tx: Sender<Event>,
    last_level: Mutex<Instant>,
    last_spectrum: Mutex<Instant>,
}

impl TuiEventSink {
    pub fn new(tx: Sender<Event>) -> Self {
        let past = Instant::now() - Duration::from_secs(10);
        Self {
            tx,
            last_level: Mutex::new(past),
            last_spectrum: Mutex::new(past),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Event {
    DeviceConnected(DeviceInfo),
    DeviceDisconnected,
    Metrics(AudioMetrics),
    UdpWarning,
    MuteChanged(bool),
    Level(u32),
    Spectrum(Vec<f32>, Vec<f32>),
    Stopped,
    WebClientCount(u32),
    InstallProgress(String),
    AecStatus(AecStatus),
}

impl ServerEvents for TuiEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        let _ = self.tx.send(Event::DeviceConnected(info));
    }
    fn device_disconnected(&self) {
        let _ = self.tx.send(Event::DeviceDisconnected);
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        let _ = self.tx.send(Event::Metrics(metrics));
    }
    fn udp_audio_warning(&self) {
        let _ = self.tx.send(Event::UdpWarning);
    }
    fn mute_state_changed(&self, is_muted: bool) {
        let _ = self.tx.send(Event::MuteChanged(is_muted));
    }
    fn audio_level(&self, level: u32) {
        let mut last = self.last_level.lock().unwrap();
        if last.elapsed() >= Duration::from_millis(70) {
            *last = Instant::now();
            drop(last);
            let _ = self.tx.send(Event::Level(level));
        }
    }
    fn audio_spectrum(&self, raw: Vec<f32>, processed: Vec<f32>) {
        let mut last = self.last_spectrum.lock().unwrap();
        if last.elapsed() >= Duration::from_millis(70) {
            *last = Instant::now();
            drop(last);
            let _ = self.tx.send(Event::Spectrum(raw, processed));
        }
    }
    fn server_stopped(&self) {
        let _ = self.tx.send(Event::Stopped);
    }
    fn web_client_count(&self, count: u32) {
        let _ = self.tx.send(Event::WebClientCount(count));
    }
    fn install_progress(&self, message: String) {
        let _ = self.tx.send(Event::InstallProgress(message));
    }
    fn aec_status_changed(&self, status: AecStatus) {
        let _ = self.tx.send(Event::AecStatus(status));
    }
}
