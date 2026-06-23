pub mod mdns;
pub mod discovery;
pub mod tcp_server;
pub mod udp_server;
#[cfg(feature = "web-server")]
pub mod web_server;

pub use mdns::NetworkManager;
pub use discovery::{query_network_interfaces, NetworkInterfaceInfo};
