/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
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

//! Runtime controls (mute, monitoring, DSP settings) shared by the frontends
//! and by the plugin host API, so every caller gets the same validation,
//! persistence and notifications.

use crate::events::SharedEvents;
use crate::plugins::PluginHost;
use crate::server::output::AudioOutputHandle;
use crate::server::ServerState;
use crate::stats::NetworkStats;
use crate::transport::tcp::SharedActiveConnection;
use crate::transport::udp::ActiveAudioSession;
use cpal::traits::{DeviceTrait, HostTrait};
use micyou_audio::dsp::AudioDspSettings;
use micyou_plugin::PluginEvent;
use micyou_protocol::micyou::{MessageWrapper, MuteMessage};
use serde::Serialize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};

/// Whether this platform has a speaker loopback path for the AEC far-end
/// reference (macOS does not).
pub const fn aec_supported() -> bool {
    !cfg!(target_os = "macos")
}

/// Names of the output devices cpal can open, sorted and deduplicated.
pub fn audio_devices() -> Vec<String> {
    let mut names: Vec<String> = match cpal::default_host().output_devices() {
        Ok(devices) => devices.filter_map(|dev| dev.name().ok()).collect(),
        Err(e) => {
            log::warn!("[Audio] listing output devices failed: {e}");
            Vec::new()
        }
    };
    names.sort();
    names.dedup();
    names
}

#[derive(Serialize)]
pub struct PipeWireStatus {
    pub available: bool,
    pub setup: bool,
    pub device_exists: bool,
    pub install_command: String,
    pub distro: String,
}

pub fn pipewire_status() -> PipeWireStatus {
    #[cfg(target_os = "linux")]
    {
        use crate::platform::pipewire;
        let (distro, install_command) = pipewire::detect_install_info();
        PipeWireStatus {
            available: pipewire::is_available(),
            setup: pipewire::is_setup(),
            device_exists: pipewire::device_exists(),
            install_command,
            distro,
        }
    }
    #[cfg(not(target_os = "linux"))]
    PipeWireStatus {
        available: false,
        setup: false,
        device_exists: false,
        install_command: String::new(),
        distro: String::new(),
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamingStatus {
    pub is_server_running: bool,
    pub is_connected: bool,
    pub is_muted: bool,
}

/// Handles on the live server state that runtime controls act on.
#[derive(Clone)]
pub struct Controls {
    pub(crate) events: SharedEvents,
    pub(crate) stats: Arc<NetworkStats>,
    pub(crate) active_connection: SharedActiveConnection,
    pub(crate) plugins: Arc<PluginHost>,
    pub(crate) is_monitoring: Arc<AtomicBool>,
    pub(crate) audio_output: Arc<AudioOutputHandle>,
    pub(crate) dsp_settings: Arc<RwLock<AudioDspSettings>>,
}

impl Controls {
    pub fn is_muted(&self) -> bool {
        self.stats.is_muted()
    }

    /// Mute locally and, unless mute sync is disabled in server.json, tell
    /// the connected phone.
    pub fn set_muted(&self, muted: bool) {
        self.stats.set_muted(muted);
        self.events.mute_state_changed(muted);
        self.plugins
            .broadcast_event(&PluginEvent::MuteChanged { muted });
        if !crate::config::load_server_prefs().mute_sync {
            return;
        }
        let Ok(connection) = self.active_connection.try_lock() else {
            log::warn!("[Mute] connection busy, mute state not sent to the phone");
            return;
        };
        let Some(connection) = connection.as_ref() else {
            return;
        };
        let message = MessageWrapper {
            mute: Some(MuteMessage {
                is_muted: Some(muted),
            }),
            ..Default::default()
        };
        if let Err(e) = connection.sender.try_send(message) {
            log::warn!("[Mute] failed to send mute state to the phone: {e}");
        }
    }

    pub fn is_monitoring(&self) -> bool {
        self.is_monitoring.load(Ordering::Relaxed)
    }

    pub fn set_monitoring(&self, enabled: bool) {
        self.is_monitoring.store(enabled, Ordering::Relaxed);
        self.audio_output.set_monitoring(enabled);
        self.events.monitoring_state_changed(enabled);
        self.plugins
            .broadcast_event(&PluginEvent::MonitoringChanged { enabled });
    }

    pub fn dsp_settings(&self) -> AudioDspSettings {
        self.dsp_settings
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// Validate, persist to settings.json and apply a full settings value.
    pub fn apply_dsp_settings(&self, mut settings: AudioDspSettings) -> Result<(), String> {
        settings.normalize();
        // AEC must always run first in the processing chain.
        if let Some(pos) = settings.processing_chain.iter().position(|s| s == "AEC") {
            let stage = settings.processing_chain.remove(pos);
            settings.processing_chain.insert(0, stage);
        }
        // Keep the per-plugin chain nodes in sync with the live registry
        // (#347): a full write must neither drop active nor keep stale nodes.
        self.plugins.reconcile_settings_chain(&mut settings);

        let mut current = self
            .dsp_settings
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if settings.aec_enabled && !current.aec_enabled && !aec_supported() {
            return Err("AEC is not supported on macOS".to_string());
        }
        crate::config::save_dsp_settings(&settings)
            .map_err(|e| format!("Failed to persist settings: {e}"))?;
        *current = settings;
        drop(current);
        self.plugins.broadcast_event(&PluginEvent::DspSettingsChanged);
        Ok(())
    }

    /// Apply a partial JSON object (or a complete settings object) on top of
    /// the current settings.
    pub fn patch_dsp_settings(&self, json: &str) -> Result<(), String> {
        let mut value = serde_json::to_value(self.dsp_settings())
            .map_err(|e| format!("serialize current dsp settings: {e}"))?;
        let patch: serde_json::Map<String, serde_json::Value> =
            serde_json::from_str(json).map_err(|e| format!("invalid dsp json: {e}"))?;
        let object = value
            .as_object_mut()
            .expect("AudioDspSettings serializes to a JSON object");
        object.extend(patch);
        let updated = serde_json::from_value(value)
            .map_err(|e| format!("invalid dsp settings: {e}"))?;
        self.apply_dsp_settings(updated)
    }
}

impl ServerState {
    pub fn controls(&self) -> Controls {
        Controls {
            events: self.events.clone(),
            stats: self.network_stats.clone(),
            active_connection: self.active_connection.clone(),
            plugins: self.plugins.clone(),
            is_monitoring: self.is_monitoring.clone(),
            audio_output: self.audio_output.clone(),
            dsp_settings: self.dsp_settings.clone(),
        }
    }

    pub fn set_spectrum_streaming(&self, enabled: bool) {
        self.spectrum_streaming_enabled
            .store(enabled, Ordering::Release);
    }

    /// Current DSP settings. Reads the shared settings.json when present so
    /// edits made by another frontend show up.
    pub fn current_dsp_settings(&self) -> AudioDspSettings {
        if crate::config::settings_path().exists() {
            crate::config::load_dsp_settings()
        } else {
            self.controls().dsp_settings()
        }
    }

    pub async fn streaming_status(&self) -> StreamingStatus {
        let is_server_running = matches!(
            self.lifecycle.lock().await.phase(),
            crate::server::lifecycle::ServerLifecyclePhase::Running
        );
        let control_connected = self.active_connection.lock().await.is_some();
        let audio_active = !matches!(
            *self
                .active_audio_session
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner()),
            ActiveAudioSession::Inactive
        );
        #[cfg(feature = "web-server")]
        let web_connected = self
            .web_server
            .lock()
            .await
            .as_ref()
            .is_some_and(|web| web.client_count() > 0);
        #[cfg(not(feature = "web-server"))]
        let web_connected = false;

        StreamingStatus {
            is_server_running,
            is_connected: control_connected || audio_active || web_connected,
            is_muted: self.network_stats.is_muted(),
        }
    }
}
