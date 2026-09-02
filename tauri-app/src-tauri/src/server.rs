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

use crate::stats::NetworkStats;
use micyou_audio::dsp::AudioDspSettings;
use std::sync::Arc;
use std::sync::RwLock;
use tokio::sync::{oneshot, Mutex, OwnedMutexGuard};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

pub const STARTUP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);
pub const AUDIO_JOIN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ServerLifecyclePhase {
    #[default]
    Stopped,
    Starting,
    Running,
    Stopping,
    StoppingResidual,
}

enum AudioThreadState {
    None,
    Owned(std::thread::JoinHandle<()>),
    Joining(JoinHandle<Result<(), String>>),
}

pub struct ServerLifecycleState {
    phase: ServerLifecyclePhase,
    audio_thread: AudioThreadState,
}

impl Default for ServerLifecycleState {
    fn default() -> Self {
        Self {
            phase: ServerLifecyclePhase::Stopped,
            audio_thread: AudioThreadState::None,
        }
    }
}

pub async fn await_startup_ready(
    ready: oneshot::Receiver<Result<(), String>>,
    component: &str,
    timeout_duration: std::time::Duration,
) -> Result<(), String> {
    match tokio::time::timeout(timeout_duration, ready).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) => Err(format!("{} exited during startup", component)),
        Err(_) => Err(format!("{} startup timed out", component)),
    }
}

#[derive(Clone, Default)]
pub struct ServerLifecycleGate {
    lock: Arc<Mutex<()>>,
}

impl ServerLifecycleGate {
    pub async fn enter(&self) -> OwnedMutexGuard<()> {
        self.lock.clone().lock_owned().await
    }
}

impl ServerLifecycleState {
    pub async fn begin_start(&mut self) -> Result<(), String> {
        if let AudioThreadState::Joining(join) = &mut self.audio_thread {
            if !join.is_finished() {
                return Err(
                    "Server is still stopping: the previous audio thread has not exited"
                        .to_string(),
                );
            }
            let result = join
                .await
                .map_err(|error| format!("Audio cleanup task failed: {}", error))?;
            self.audio_thread = AudioThreadState::None;
            self.phase = ServerLifecyclePhase::Stopped;
            result?;
        }
        if self.phase != ServerLifecyclePhase::Stopped {
            return Err(format!(
                "Server cannot start while lifecycle is {:?}",
                self.phase
            ));
        }
        self.phase = ServerLifecyclePhase::Starting;
        Ok(())
    }

    pub fn set_audio_thread(&mut self, thread: std::thread::JoinHandle<()>) {
        self.audio_thread = AudioThreadState::Owned(thread);
    }

    pub fn mark_running(&mut self) {
        self.phase = ServerLifecyclePhase::Running;
    }

    pub fn begin_stopping(&mut self) {
        self.phase = ServerLifecyclePhase::Stopping;
    }

    pub fn mark_stopped_without_audio(&mut self) {
        if matches!(self.audio_thread, AudioThreadState::None) {
            self.phase = ServerLifecyclePhase::Stopped;
        }
    }

    pub async fn join_audio_bounded(
        &mut self,
        timeout_duration: std::time::Duration,
    ) -> Result<(), String> {
        if let AudioThreadState::Owned(thread) =
            std::mem::replace(&mut self.audio_thread, AudioThreadState::None)
        {
            self.audio_thread = AudioThreadState::Joining(tokio::task::spawn_blocking(move || {
                thread
                    .join()
                    .map_err(|_| "Audio thread panicked during shutdown".to_string())
            }));
        }

        let AudioThreadState::Joining(join) = &mut self.audio_thread else {
            self.phase = ServerLifecyclePhase::Stopped;
            return Ok(());
        };
        match tokio::time::timeout(timeout_duration, &mut *join).await {
            Ok(result) => {
                let result =
                    result.map_err(|error| format!("Audio cleanup task failed: {}", error))?;
                self.audio_thread = AudioThreadState::None;
                self.phase = ServerLifecyclePhase::Stopped;
                result
            }
            Err(_) => {
                self.phase = ServerLifecyclePhase::StoppingResidual;
                Err("Server stopped with residual audio cleanup: audio thread did not exit within 3 seconds".to_string())
            }
        }
    }

    pub fn phase(&self) -> ServerLifecyclePhase {
        self.phase
    }
}

impl Default for ServerState {
    fn default() -> Self {
        let audio_output = crate::audio_output::AudioOutputHandle::spawn();
        Self {
            lifecycle_gate: ServerLifecycleGate::default(),
            lifecycle: Arc::new(Mutex::new(ServerLifecycleState::default())),
            cancel_token: Arc::new(Mutex::new(None)),
            background_tasks: Arc::new(Mutex::new(Vec::new())),
            mdns_manager: Arc::new(Mutex::new(None)),
            dsp_settings: Arc::new(RwLock::new(AudioDspSettings::default())),
            is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            spectrum_streaming_enabled: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            network_stats: Arc::new(NetworkStats::default()),
            active_connection: Arc::new(Mutex::new(None)),
            takeover_lock: Arc::new(Mutex::new(())),
            active_audio_session: Arc::new(RwLock::new(Default::default())),
            audio_output: audio_output.clone(),
            plugins: Arc::new(crate::plugins::PluginHost::new(audio_output)),
            #[cfg(feature = "web-server")]
            web_server: Arc::new(Mutex::new(None)),
            #[cfg(feature = "web-server")]
            web_mdns: Arc::new(Mutex::new(None)),
        }
    }
}

pub struct ServerState {
    pub lifecycle_gate: ServerLifecycleGate,
    pub lifecycle: Arc<Mutex<ServerLifecycleState>>,
    pub cancel_token: Arc<Mutex<Option<CancellationToken>>>,
    pub background_tasks: Arc<Mutex<Vec<JoinHandle<()>>>>,
    pub mdns_manager: Arc<Mutex<Option<crate::network::NetworkManager>>>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub is_monitoring: Arc<std::sync::atomic::AtomicBool>,
    pub spectrum_streaming_enabled: Arc<std::sync::atomic::AtomicBool>,
    pub network_stats: Arc<NetworkStats>,
    pub active_connection: crate::tcp_server::SharedActiveConnection,
    pub takeover_lock: crate::tcp_server::SharedTakeoverLock,
    pub active_audio_session: crate::udp_server::SharedActiveAudioSession,
    /// Persistent audio output device. A dedicated thread owns the cpal stream
    /// for the whole process; it is opened at app startup (or lazily on the
    /// first server start for CLI/TUI) and only closed when the process exits.
    /// Server start/stop and phone connect/disconnect never tear it down.
    pub audio_output: Arc<crate::audio_output::AudioOutputHandle>,
    /// Plugin host: manager + DSP node registry, shared with the audio thread.
    pub plugins: Arc<crate::plugins::PluginHost>,
    #[cfg(feature = "web-server")]
    pub web_server: Arc<Mutex<Option<crate::web_server::WebServer>>>,
    #[cfg(feature = "web-server")]
    pub web_mdns: Arc<Mutex<Option<crate::network::NetworkManager>>>,
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
pub struct NetworkInfo {
    pub ips: Vec<String>,
    pub port: u16,
}

#[cfg(test)]
mod tests {
    use super::{
        await_startup_ready, ServerLifecycleGate, ServerLifecyclePhase, ServerLifecycleState,
    };
    use std::sync::Arc;
    use tokio::sync::Notify;

    #[tokio::test]
    async fn lifecycle_gate_serializes_complete_transactions() {
        let gate = ServerLifecycleGate::default();
        let first_entered = Arc::new(Notify::new());
        let release_first = Arc::new(Notify::new());
        let second_entered = Arc::new(Notify::new());

        let first = {
            let gate = gate.clone();
            let first_entered = first_entered.clone();
            let release_first = release_first.clone();
            tokio::spawn(async move {
                let _guard = gate.enter().await;
                first_entered.notify_one();
                release_first.notified().await;
            })
        };
        first_entered.notified().await;

        let second = {
            let gate = gate.clone();
            let second_entered = second_entered.clone();
            tokio::spawn(async move {
                let _guard = gate.enter().await;
                second_entered.notify_one();
            })
        };

        assert!(
            tokio::time::timeout(
                std::time::Duration::from_millis(50),
                second_entered.notified()
            )
            .await
            .is_err(),
            "a second lifecycle transaction entered before the first completed"
        );

        release_first.notify_one();
        tokio::time::timeout(std::time::Duration::from_secs(1), second_entered.notified())
            .await
            .expect("the second lifecycle transaction did not enter after release");

        first.await.unwrap();
        second.await.unwrap();
    }

    #[tokio::test]
    async fn startup_ready_times_out_with_component_name() {
        let (_sender, receiver) = tokio::sync::oneshot::channel();
        let error = await_startup_ready(
            receiver,
            "Audio output",
            std::time::Duration::from_millis(20),
        )
        .await
        .unwrap_err();
        assert_eq!(error, "Audio output startup timed out");
    }

    #[tokio::test]
    async fn residual_audio_thread_blocks_restart_until_it_exits() {
        let release = Arc::new((std::sync::Mutex::new(false), std::sync::Condvar::new()));
        let thread_release = release.clone();
        let thread = std::thread::spawn(move || {
            let (lock, condition) = &*thread_release;
            let mut released = lock.lock().unwrap();
            while !*released {
                released = condition.wait(released).unwrap();
            }
        });
        let mut lifecycle = ServerLifecycleState::default();
        lifecycle.begin_start().await.unwrap();
        lifecycle.set_audio_thread(thread);
        lifecycle.begin_stopping();

        let error = lifecycle
            .join_audio_bounded(std::time::Duration::from_millis(20))
            .await
            .unwrap_err();
        assert!(error.contains("residual audio cleanup"));
        assert_eq!(lifecycle.phase(), ServerLifecyclePhase::StoppingResidual);
        assert!(lifecycle.begin_start().await.is_err());

        let (lock, condition) = &*release;
        *lock.lock().unwrap() = true;
        condition.notify_one();
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        lifecycle.begin_start().await.unwrap();
        assert_eq!(lifecycle.phase(), ServerLifecyclePhase::Starting);
    }
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
pub struct NetworkInterfaceInfo {
    pub ip: String,
    pub interface_name: String,
}

const VIRTUAL_KEYWORDS: &[&str] = &[
    "vmware",
    "virtualbox",
    "hyper-v",
    "vethernet",
    "wsl",
    "docker",
    "tunnel",
    "teredo",
    "isatap",
    "vpn",
    "tailscale",
    "clash",
    "flclash",
];

pub fn score_ip(ip: &str) -> i32 {
    if ip.starts_with("192.168.") {
        100
    } else if ip.starts_with("172.") {
        if let Some(second) = ip.split('.').nth(1) {
            if let Ok(n) = second.parse::<u32>() {
                if (16..=31).contains(&n) {
                    return 80;
                }
            }
        }
        0
    } else if ip.starts_with("10.") {
        50
    } else if ip.starts_with("198.18.") {
        -10
    } else if ip.starts_with("169.254.") {
        -20
    } else {
        0
    }
}

pub fn query_network_interfaces() -> Vec<NetworkInterfaceInfo> {
    let mut candidates: Vec<(std::net::IpAddr, String)> = Vec::new();
    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        for (name, ip) in interfaces {
            if ip.is_loopback() || !ip.is_ipv4() {
                continue;
            }
            let name_lower = name.to_lowercase();
            if VIRTUAL_KEYWORDS.iter().any(|kw| name_lower.contains(kw)) {
                continue;
            }
            candidates.push((ip, name));
        }
    }

    candidates.sort_by(|a, b| {
        let score_a = score_ip(&a.0.to_string());
        let score_b = score_ip(&b.0.to_string());
        score_b
            .cmp(&score_a)
            .then_with(|| a.0.to_string().cmp(&b.0.to_string()))
            .then_with(|| a.1.cmp(&b.1))
    });

    let result: Vec<NetworkInterfaceInfo> = candidates
        .into_iter()
        .map(|(ip, name)| NetworkInterfaceInfo {
            ip: ip.to_string(),
            interface_name: name,
        })
        .collect();

    if result.is_empty() {
        vec![NetworkInterfaceInfo {
            ip: "127.0.0.1".to_string(),
            interface_name: "Local".to_string(),
        }]
    } else {
        result
    }
}
