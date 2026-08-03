use std::sync::mpsc::Sender;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri_app_lib::events::{AecStatus, ServerEvents};
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

/// Server events consumed by the interactive terminal UI.
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

/// Forward server events to the TUI channel while throttling high-frequency
/// audio visualization updates to the terminal's useful refresh rate.
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
