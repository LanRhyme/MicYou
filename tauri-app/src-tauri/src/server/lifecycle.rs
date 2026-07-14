use crate::error::AppError;
use crate::network::mdns::NetworkManager;
use crate::network::stats::NetworkStats;
use crate::platform::RawSocketHandle;
use micyou_audio::dsp::AudioDspSettings;
use micyou_audio::pipeline::{AudioPipeline, PipelineConfig, PipelineEvent};
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, Mutex};
use tokio_util::sync::CancellationToken;

// Keep struct shape consistent regardless of web-server feature.
// On non-web builds, WebServerInner is () and web_server is always None.
#[cfg(feature = "web-server")]
type WebServerInner = crate::server::web::WebServer;
#[cfg(not(feature = "web-server"))]
type WebServerInner = ();

pub struct ServerHandle {
    cancel_token: CancellationToken,
    #[allow(dead_code)]
    pipeline: AudioPipeline,
    mdns: Option<NetworkManager>,
    web_server: Option<WebServerInner>,
    web_mdns: Option<NetworkManager>,
    connection_tx: Arc<Mutex<Option<mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>>>,
    active_socket_handle: Arc<Mutex<Option<RawSocketHandle>>>,
    _event_task: tokio::task::JoinHandle<()>,
}

pub struct ServerConfig {
    pub port: u16,
    pub mode: String,
    pub bind_address: String,
    pub output_device: Option<String>,
    pub model_dir: Option<std::path::PathBuf>,
}

impl ServerHandle {
    pub async fn start(
        config: ServerConfig,
        app: AppHandle,
        dsp_settings: Arc<std::sync::RwLock<AudioDspSettings>>,
        stats: Arc<NetworkStats>,
    ) -> Result<Self, AppError> {
        // Platform-specific setup
        #[cfg(target_os = "linux")]
        {
            if config.output_device.is_none() && crate::platform::linux_pipewire::is_available() {
                if !crate::platform::linux_pipewire::is_setup() {
                    log::info!("[PipeWire] Setting up virtual audio device...");
                    if !crate::platform::linux_pipewire::setup() {
                        log::warn!("[PipeWire] Setup failed, falling back to default device");
                    }
                }
            }
        }

        // Spawn audio pipeline with an event channel
        let (event_tx, mut event_rx) = mpsc::channel::<PipelineEvent>(64);
        let pipeline_config = PipelineConfig {
            output_device: config.output_device,
            dsp_settings: dsp_settings.clone(),
            model_dir: config.model_dir,
            is_web_mode: config.mode == "web",
        };
        let (pipeline, ready_rx) = AudioPipeline::spawn(pipeline_config, event_tx);

        // Forward pipeline events to Tauri frontend
        let app_for_events = app.clone();
        let event_task = tokio::spawn(async move {
            while let Some(event) = event_rx.recv().await {
                match event {
                    PipelineEvent::AudioLevel(level) => {
                        let _ = app_for_events.emit("audio-level", level);
                    }
                    PipelineEvent::AudioSpectrum { raw, processed } => {
                        let _ = app_for_events.emit(
                            "audio-spectrum",
                            serde_json::json!({
                                "raw": raw,
                                "processed": processed,
                            }),
                        );
                    }
                }
            }
        });

        // Wait for audio pipeline to be ready
        ready_rx
            .await
            .map_err(|_| AppError::audio("Audio pipeline panicked"))?
            .map_err(|e| AppError::audio(e))?;

        let cancel_token = CancellationToken::new();
        let audio_tx = pipeline.sender().clone();
        let connection_tx: Arc<
            Mutex<Option<mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>>,
        > = Arc::new(Mutex::new(None));
        let active_socket_handle: Arc<Mutex<Option<RawSocketHandle>>> = Arc::new(Mutex::new(None));

        // Web mode
        #[cfg(feature = "web-server")]
        if config.mode == "web" {
            let web = crate::server::web::WebServer::new();
            let (web_audio_tx, mut web_audio_rx) =
                mpsc::channel::<micyou_protocol::micyou::AudioPacketMessage>(1024);
            web.start(config.port, app.clone(), web_audio_tx)
                .await
                .map_err(|e| AppError::network(e))?;

            // Bridge web audio into the pipeline
            let audio_tx_web = audio_tx;
            tokio::spawn(async move {
                let mut seq: i32 = 0;
                while let Some(packet) = web_audio_rx.recv().await {
                    let ordered = micyou_protocol::micyou::AudioPacketMessageOrdered {
                        sequence_number: seq,
                        audio_packet: Some(packet),
                        timestamp: 0,
                        fec_buffer: Vec::new(),
                        fec_sequence_number: -1,
                    };
                    seq += 1;
                    if audio_tx_web.send(ordered).await.is_err() {
                        break;
                    }
                }
            });

            let web_mdns = NetworkManager::start_web_mdns(config.port, &config.bind_address).ok();
            return Ok(Self {
                cancel_token,
                pipeline,
                mdns: None,
                web_server: Some(web),
                web_mdns,
                connection_tx,
                active_socket_handle,
                _event_task: event_task,
            });
        }

        #[cfg(not(feature = "web-server"))]
        if config.mode == "web" {
            return Err(AppError::other("Web server feature not enabled"));
        }

        // Start TCP server
        let app_tcp = app.clone();
        let token_tcp = cancel_token.clone();
        let audio_tx_tcp = audio_tx.clone();
        let stats_tcp = stats.clone();
        let mode_tcp = config.mode.clone();
        let bind_tcp = config.bind_address.clone();
        let conn_tx_tcp = connection_tx.clone();
        let socket_handle_tcp = active_socket_handle.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(e) = crate::server::tcp::start_tcp_server(
                app_tcp,
                config.port,
                bind_tcp,
                token_tcp,
                audio_tx_tcp,
                stats_tcp,
                mode_tcp,
                conn_tx_tcp,
                socket_handle_tcp,
            )
            .await
            {
                log::error!(target: "tcp", "TCP Server error: {}", e);
            }
        });

        // Start UDP server
        let token_udp = cancel_token.clone();
        let stats_udp = stats.clone();
        let bind_udp = config.bind_address.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(e) = crate::server::udp::start_udp_server(
                audio_tx,
                config.port + 1,
                bind_udp,
                token_udp,
                stats_udp,
            )
            .await
            {
                log::error!(target: "udp", "UDP Server error: {}", e);
            }
        });

        // Register mDNS
        let mdns = NetworkManager::start_mdns(config.port, &config.bind_address)
            .map_err(|e| {
                log::error!(target: "mdns", "Failed to start mDNS: {}", e);
                let _ = app.emit("mdns-error", e.to_string());
            })
            .ok();

        Ok(Self {
            cancel_token,
            pipeline,
            mdns,
            web_server: None,
            web_mdns: None,
            connection_tx,
            active_socket_handle,
            _event_task: event_task,
        })
    }

    pub fn connection_sender(
        &self,
    ) -> Arc<Mutex<Option<mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>>> {
        self.connection_tx.clone()
    }

    /// Returns (is_running, client_count) for the web server, if any.
    pub fn web_status(&self) -> (bool, u32) {
        #[cfg(feature = "web-server")]
        if let Some(web) = &self.web_server {
            return (web.is_running(), web.client_count() as u32);
        }
        (false, 0)
    }

    pub async fn stop(self, app: &AppHandle) {
        // Force-close active socket
        {
            let mut handle = self.active_socket_handle.lock().await;
            if let Some(raw) = handle.take() {
                crate::server::tcp::force_close_socket(raw);
            }
        }

        // Clear connection channel
        {
            self.connection_tx.lock().await.take();
        }

        // Stop web server (only meaningful when the feature is enabled)
        #[cfg(feature = "web-server")]
        if let Some(web) = &self.web_server {
            web.stop();
        }

        // Cancel token — TCP/UDP tasks exit
        self.cancel_token.cancel();

        // Unregister mDNS
        if let Some(mdns) = &self.mdns {
            mdns.stop_mdns();
        }
        #[cfg(feature = "web-server")]
        if let Some(web_mdns) = &self.web_mdns {
            web_mdns.stop_mdns();
        }

        // Platform-specific cleanup
        #[cfg(target_os = "macos")]
        {
            let _ = crate::platform::macos_blackhole::do_restore_input_device().await;
        }
        #[cfg(target_os = "linux")]
        {
            crate::platform::linux_pipewire::cleanup();
        }

        self._event_task.abort();
        let _ = app.emit("server-stopped", ());
    }
}
