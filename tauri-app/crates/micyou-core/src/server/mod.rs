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

//! Server state shared by the GUI, CLI and TUI, and the lifecycle
//! operations on it.

mod audio_pipeline;
pub mod lifecycle;
pub mod output;
pub mod service;

use crate::events::SharedEvents;
use crate::host::SharedHost;
use crate::plugins::{PluginHost, ServerHandles};
use crate::stats::NetworkStats;
use crate::transport::tcp::{SharedActiveConnection, SharedTakeoverLock};
use crate::transport::udp::{ActiveAudioSession, SharedActiveAudioSession};
use lifecycle::{ServerLifecycleGate, ServerLifecycleState};
use micyou_audio::dsp::AudioDspSettings;
use output::AudioOutputHandle;
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, RwLock};
use tokio::sync::Mutex;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

pub use service::{start_server, stop_server, StartRequest};

pub struct ServerState {
    pub events: SharedEvents,
    /// The frontend's own resource directory, if it knows one (see
    /// [`crate::platform::resources::find_resource_dir`]).
    pub resource_hint: Option<PathBuf>,
    pub lifecycle_gate: ServerLifecycleGate,
    pub lifecycle: Arc<Mutex<ServerLifecycleState>>,
    pub cancel_token: Arc<Mutex<Option<CancellationToken>>>,
    pub background_tasks: Arc<Mutex<Vec<JoinHandle<()>>>>,
    pub mdns_manager: Arc<Mutex<Option<crate::discovery::NetworkManager>>>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub is_monitoring: Arc<AtomicBool>,
    pub spectrum_streaming_enabled: Arc<AtomicBool>,
    pub network_stats: Arc<NetworkStats>,
    pub active_connection: SharedActiveConnection,
    pub takeover_lock: SharedTakeoverLock,
    pub active_audio_session: SharedActiveAudioSession,
    pub audio_output: Arc<AudioOutputHandle>,
    pub plugins: Arc<PluginHost>,
    #[cfg(feature = "web-server")]
    pub web_server: Arc<Mutex<Option<crate::transport::web::WebServer>>>,
    #[cfg(feature = "web-server")]
    pub web_mdns: Arc<Mutex<Option<crate::discovery::NetworkManager>>>,
}

impl ServerState {
    /// Build the state with DSP settings loaded from the shared settings.json.
    pub fn new(events: SharedEvents, host: SharedHost, resource_hint: Option<PathBuf>) -> Self {
        let network_stats = Arc::new(NetworkStats::default());
        // The output engine watches the stats' mute flag directly: switching to
        // muted drops queued audio and outputs silence within one callback period.
        let audio_output = AudioOutputHandle::spawn_with_mute_flag(network_stats.mute_flag());
        let active_connection = Arc::new(Mutex::new(None));
        let active_audio_session = Arc::new(RwLock::new(ActiveAudioSession::Inactive));
        let lifecycle = Arc::new(Mutex::new(ServerLifecycleState::default()));
        #[cfg(feature = "web-server")]
        let web_server = Arc::new(Mutex::new(None));

        let plugins = PluginHost::new(
            ServerHandles {
                network_stats: network_stats.clone(),
                audio_output: audio_output.clone(),
                active_connection: active_connection.clone(),
                active_audio_session: active_audio_session.clone(),
                lifecycle: lifecycle.clone(),
                #[cfg(feature = "web-server")]
                web_server: web_server.clone(),
            },
            host,
        );

        let state = Self {
            events,
            resource_hint,
            lifecycle_gate: ServerLifecycleGate::default(),
            lifecycle,
            cancel_token: Arc::new(Mutex::new(None)),
            background_tasks: Arc::new(Mutex::new(Vec::new())),
            mdns_manager: Arc::new(Mutex::new(None)),
            dsp_settings: Arc::new(RwLock::new(crate::config::load_dsp_settings())),
            is_monitoring: Arc::new(AtomicBool::new(false)),
            spectrum_streaming_enabled: Arc::new(AtomicBool::new(false)),
            network_stats,
            active_connection,
            takeover_lock: Arc::new(Mutex::new(())),
            active_audio_session,
            audio_output,
            plugins,
            #[cfg(feature = "web-server")]
            web_server,
            #[cfg(feature = "web-server")]
            web_mdns: Arc::new(Mutex::new(None)),
        };
        state.plugins.attach_controls(state.controls());
        state
    }

    /// Resolved directory of bundled models and configs.
    pub fn resource_dir(&self) -> Option<PathBuf> {
        crate::platform::resources::find_resource_dir(self.resource_hint.as_deref())
    }
}
