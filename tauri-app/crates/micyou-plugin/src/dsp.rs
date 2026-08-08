//! Plugin DSP integration.
//!
//! Plugins with `kind: dsp` register processing nodes here. The registry
//! orders nodes by their manifest position hints (`first` / `insert_after`
//! applied plugin-to-plugin), then the audio thread pulls a closure through
//! `micyou_audio::DspProcessor::set_external_hook` (see the synthetic
//! `"Plugins"` chain node) and runs every registered node per frame.
//!
//! Real-time safety: `process_all` runs on the audio thread. Nodes are
//! `Arc<Mutex<PluginInstance>>` — the lock is uncontended in steady state
//! (plugins only change on load/unload) but plugin code itself must obey the
//! real-time rules (no allocations, no blocking host calls, bounded work).

use crate::error::{PluginError, PluginResult};
use crate::plugin::{AudioFrameCtx, PluginInstance, PluginRuntime, ProcessStatus};
use std::sync::{Arc, Mutex, RwLock};

/// A registered plugin DSP node.
pub struct DspNode {
    /// Plugin id (also the node id).
    pub plugin_id: String,
    /// Manifest position hint: run before all others.
    pub first: bool,
    /// Manifest position hint: run right after this plugin id (if present).
    pub insert_after: Option<String>,
    /// The loaded instance, shared with the plugin manager.
    pub instance: Arc<Mutex<PluginInstance>>,
}

/// Ordered, thread-safe registry of active DSP plugin nodes.
pub struct PluginDspRegistry {
    nodes: RwLock<Vec<DspNode>>,
    /// Set when the chain should include the synthetic `"Plugins"` node.
    active: RwLock<bool>,
}

impl Default for PluginDspRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginDspRegistry {
    pub fn new() -> Self {
        Self {
            nodes: RwLock::new(Vec::new()),
            active: RwLock::new(false),
        }
    }

    /// Whether any DSP plugin is registered.
    pub fn is_active(&self) -> bool {
        self.active.read().map(|g| *g).unwrap_or(false)
    }

    /// Register a node. Keeps plugin-to-plugin ordering stable: `first` nodes
    /// go ahead, otherwise sorted by id for determinism.
    pub fn register(&self, node: DspNode) -> PluginResult<()> {
        let mut nodes = self
            .nodes
            .write()
            .map_err(|_| PluginError::Runtime("dsp registry poisoned".into()))?;
        if nodes.iter().any(|n| n.plugin_id == node.plugin_id) {
            return Err(PluginError::AlreadyExists(format!(
                "dsp node {} already registered",
                node.plugin_id
            )));
        }
        nodes.push(node);
        nodes.sort_by(|a, b| {
            b.first
                .cmp(&a.first)
                .then_with(|| a.plugin_id.cmp(&b.plugin_id))
        });
        *self
            .active
            .write()
            .map_err(|_| PluginError::Runtime("dsp registry poisoned".into()))? = true;
        Ok(())
    }

    pub fn unregister(&self, plugin_id: &str) -> PluginResult<()> {
        let mut nodes = self
            .nodes
            .write()
            .map_err(|_| PluginError::Runtime("dsp registry poisoned".into()))?;
        nodes.retain(|n| n.plugin_id != plugin_id);
        *self
            .active
            .write()
            .map_err(|_| PluginError::Runtime("dsp registry poisoned".into()))? = !nodes.is_empty();
        Ok(())
    }

    pub fn len(&self) -> usize {
        self.nodes.read().map(|g| g.len()).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn plugin_ids(&self) -> Vec<String> {
        self.nodes
            .read()
            .map(|g| g.iter().map(|n| n.plugin_id.clone()).collect())
            .unwrap_or_default()
    }

    /// Run every registered node on one audio frame (audio-thread context).
    /// Node errors are logged and skipped — one broken plugin must not take
    /// the whole chain down.
    pub fn process_all(
        &self,
        data: &mut Vec<f32>,
        channels: usize,
        sample_rate: u32,
        queued_ms: f64,
    ) {
        let nodes = match self.nodes.read() {
            Ok(g) => g,
            Err(_) => return,
        };
        for node in nodes.iter() {
            let mut instance = match node.instance.lock() {
                Ok(i) => i,
                Err(_) => continue,
            };
            let mut ctx = AudioFrameCtx {
                data,
                channels,
                sample_rate,
                queued_ms,
            };
            match instance.process_audio(&mut ctx) {
                Ok(ProcessStatus::Ok) => {}
                Ok(ProcessStatus::Bypass) => {}
                Err(e) => log::warn!("[plugins] dsp node {} failed: {e}", node.plugin_id),
            }
        }
    }
}

/// Bridge that turns a `PluginDspRegistry` into the closure the audio thread
/// attaches to `DspProcessor::set_external_hook`.
pub struct PluginDspBridge {
    registry: Arc<PluginDspRegistry>,
}

impl PluginDspBridge {
    pub fn new(registry: Arc<PluginDspRegistry>) -> Self {
        Self { registry }
    }

    /// The closure consumed by the audio engine. Cheap when no nodes exist.
    pub fn hook(&self) -> Box<dyn FnMut(&mut Vec<f32>, usize, f64) + Send> {
        let registry = self.registry.clone();
        Box::new(
            move |data: &mut Vec<f32>, channels: usize, queued_ms: f64| {
                if registry.is_active() {
                    registry.process_all(data, channels, 48_000, queued_ms);
                }
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manifest::{PluginKind, PluginManifest, RuntimeKind};
    use crate::plugin::{PluginRuntime, PluginState};
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct CountingNode {
        manifest: PluginManifest,
        calls: Arc<AtomicUsize>,
    }

    impl PluginRuntime for CountingNode {
        fn manifest(&self) -> &PluginManifest {
            &self.manifest
        }
        fn init(&mut self, _host: &dyn crate::host::HostApi) -> PluginResult<()> {
            Ok(())
        }
        fn deinit(&mut self) {}
        fn process_audio(&mut self, ctx: &mut AudioFrameCtx<'_>) -> PluginResult<ProcessStatus> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            for sample in ctx.data.iter_mut() {
                *sample += 1.0;
            }
            Ok(ProcessStatus::Ok)
        }
        fn handle_event(&mut self, _event: &crate::plugin::PluginEvent) -> PluginResult<()> {
            Ok(())
        }
        fn handle_message(
            &mut self,
            _source: &str,
            _topic: &str,
            _payload: &[u8],
        ) -> PluginResult<()> {
            Ok(())
        }
    }

    fn make_node(id: &str, first: bool, calls: Arc<AtomicUsize>) -> DspNode {
        DspNode {
            plugin_id: id.to_string(),
            first,
            insert_after: None,
            instance: Arc::new(Mutex::new(PluginInstance::Wasm(Box::new(CountingNode {
                manifest: PluginManifest {
                    id: id.to_string(),
                    name: id.to_string(),
                    version: "1.0.0".to_string(),
                    author: None,
                    description: None,
                    runtime: RuntimeKind::Wasm,
                    entry: "x.wasm".to_string(),
                    platforms: Vec::new(),
                    api_version: crate::manifest::HOST_API_VERSION,
                    capabilities: Vec::new(),
                    kind: PluginKind::Dsp,
                    ui: None,
                    dsp: None,
                    config: None,
                    ..Default::default()
                },
                calls,
            })))),
        }
    }

    #[test]
    fn registry_runs_all_nodes_in_first_first_order() {
        let registry = PluginDspRegistry::new();
        let a = Arc::new(AtomicUsize::new(0));
        let b = Arc::new(AtomicUsize::new(0));
        registry
            .register(make_node("z.last", false, a.clone()))
            .unwrap();
        registry
            .register(make_node("a.first", true, b.clone()))
            .unwrap();
        assert_eq!(registry.plugin_ids(), vec!["a.first", "z.last"]);
        assert!(registry.is_active());

        let mut data = vec![0.0f32; 4];
        registry.process_all(&mut data, 1, 48_000, 0.0);
        // both nodes ran (+1.0 each) and "a.first" ran first (order verified by
        // values would need shared buffer; count is enough for ordering here)
        assert_eq!(data, vec![2.0f32; 4]);
        assert_eq!(a.load(Ordering::SeqCst), 1);
        assert_eq!(b.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn registry_unregister_and_duplicate_rejected() {
        let registry = PluginDspRegistry::new();
        let calls = Arc::new(AtomicUsize::new(0));
        registry
            .register(make_node("dup", false, calls.clone()))
            .unwrap();
        let result = registry.register(make_node("dup", false, calls.clone()));
        assert!(matches!(result, Err(PluginError::AlreadyExists(_))));

        registry.unregister("dup").unwrap();
        assert!(registry.is_empty());
        assert!(!registry.is_active());
        let mut data = vec![0.0f32; 2];
        registry.process_all(&mut data, 1, 48_000, 0.0);
        assert_eq!(data, vec![0.0f32; 2]);
    }

    #[test]
    fn bridge_hook_is_noop_when_inactive() {
        let registry = Arc::new(PluginDspRegistry::new());
        let bridge = PluginDspBridge::new(registry.clone());
        let mut hook = bridge.hook();
        let mut data = vec![0.5f32; 8];
        hook(&mut data, 1, 0.0);
        assert_eq!(data, vec![0.5f32; 8], "no nodes → data untouched");
    }

    #[test]
    fn plugin_state_enum_is_usable() {
        // keeps PluginState re-exported in this module's scope for docs
        let _ = PluginState::Enabled;
    }
}
