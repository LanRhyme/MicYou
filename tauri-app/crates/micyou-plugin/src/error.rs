use std::fmt;

/// Errors produced by the plugin framework (manifest parsing, loading, runtime calls).
#[derive(Debug, Clone, PartialEq)]
pub enum PluginError {
    /// The plugin directory or entry artifact does not exist.
    NotFound(String),
    /// The manifest file is missing, unreadable or malformed.
    InvalidManifest(String),
    /// The manifest failed semantic validation (id charset, semver, capability, ...).
    Validation(String),
    /// The plugin id is not registered in the manager.
    UnknownPlugin(String),
    /// The plugin is not loaded (native lib / wasm module not instantiated).
    NotLoaded(String),
    /// The runtime failed to load or link the entry artifact.
    LoadFailed(String),
    /// The Host API version declared by the plugin is unsupported.
    ApiVersionMismatch { plugin: u32, host: u32 },
    /// A capability declared in the manifest was denied by the host policy.
    PermissionDenied(String),
    /// The plugin already exists / is already enabled (state conflicts).
    AlreadyExists(String),
    /// A runtime call (init / process / handle_message) returned an error.
    Runtime(String),
    /// Cross-device message delivery failed (target offline, bus closed).
    MessageDelivery(String),
    /// I/O failure while persisting plugin state.
    Io(String),
}

impl fmt::Display for PluginError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PluginError::NotFound(what) => write!(f, "plugin artifact not found: {what}"),
            PluginError::InvalidManifest(reason) => write!(f, "invalid plugin manifest: {reason}"),
            PluginError::Validation(reason) => {
                write!(f, "plugin manifest validation failed: {reason}")
            }
            PluginError::UnknownPlugin(id) => write!(f, "unknown plugin: {id}"),
            PluginError::NotLoaded(id) => write!(f, "plugin is not loaded: {id}"),
            PluginError::LoadFailed(reason) => write!(f, "plugin load failed: {reason}"),
            PluginError::ApiVersionMismatch { plugin, host } => {
                write!(
                    f,
                    "plugin requires Host API v{plugin}, host provides v{host}"
                )
            }
            PluginError::PermissionDenied(cap) => write!(f, "capability not granted: {cap}"),
            PluginError::AlreadyExists(what) => write!(f, "plugin already exists: {what}"),
            PluginError::Runtime(reason) => write!(f, "plugin runtime error: {reason}"),
            PluginError::MessageDelivery(reason) => {
                write!(f, "plugin message delivery failed: {reason}")
            }
            PluginError::Io(reason) => write!(f, "plugin state I/O failed: {reason}"),
        }
    }
}

impl std::error::Error for PluginError {}

impl From<std::io::Error> for PluginError {
    fn from(error: std::io::Error) -> Self {
        PluginError::Io(error.to_string())
    }
}

impl From<wasmi::Error> for PluginError {
    fn from(error: wasmi::Error) -> Self {
        PluginError::Runtime(error.to_string())
    }
}

impl From<serde_json::Error> for PluginError {
    fn from(error: serde_json::Error) -> Self {
        PluginError::InvalidManifest(error.to_string())
    }
}

pub type PluginResult<T> = Result<T, PluginError>;
