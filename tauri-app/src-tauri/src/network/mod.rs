pub mod interface;
pub mod mdns;

/// Re-point to the existing `src/stats.rs` so it can be accessed as
/// `crate::network::stats` without moving the file.
#[path = "../stats.rs"]
pub mod stats;

pub use interface::*;
pub use mdns::NetworkManager;
