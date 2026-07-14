pub mod micyou {
    include!(concat!(env!("OUT_DIR"), "/micyou.rs"));
}

pub mod frame;

#[derive(serde::Serialize, Clone, Debug)]
pub struct DeviceInfo {
    pub name: String,
    pub ip: String,
    pub latency: u32,
}

pub const PACKET_MAGIC: i32 = 0x4D696359; // "MicY"
pub const UDP_PACKET_MAGIC: i32 = 0x4D696355; // "MicU"
pub const PORT: u16 = 9123;
pub const UDP_PORT: u16 = 9124;
pub const MDNS_SERVICE_TYPE: &str = "_micyou._tcp.local.";
pub const MDNS_WEB_SERVICE_TYPE: &str = "_micyou-web._tcp.local.";
pub const HANDSHAKE_CLIENT_STR: &[u8] = b"MicYouCheck1";
pub const HANDSHAKE_SERVER_STR: &[u8] = b"MicYouCheck2";
