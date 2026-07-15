use std::sync::Arc;
use std::sync::RwLock;
use tokio::sync::Mutex;

use crate::network::stats::NetworkStats;
use crate::server::lifecycle::ServerHandle;
use micyou_audio::dsp::AudioDspSettings;

/// Central managed state for the Tauri application.
///
/// Long-lived shared resources live here. Per-server resources are
/// encapsulated inside `ServerHandle`, which is created on `start_server`
/// and consumed on `stop_server`.
pub struct ServerState {
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub network_stats: Arc<NetworkStats>,
    /// Active server session, if any.
    pub server_handle: Arc<Mutex<Option<ServerHandle>>>,
}
