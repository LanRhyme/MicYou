use std::sync::Arc;
use std::sync::RwLock;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;
use micyou_audio::dsp::AudioDspSettings;
use crate::stats::NetworkStats;

pub struct ServerState {
    pub cancel_token: Arc<Mutex<Option<CancellationToken>>>,
    pub mdns_manager: Arc<Mutex<Option<crate::network::NetworkManager>>>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub is_monitoring: Arc<std::sync::atomic::AtomicBool>,
    pub network_stats: Arc<NetworkStats>,
    pub connection_tx: Arc<Mutex<Option<tokio::sync::mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>>>,
    #[cfg(windows)]
    pub active_socket_handle: Arc<Mutex<Option<std::os::windows::io::RawSocket>>>,
    #[cfg(unix)]
    pub active_socket_handle: Arc<Mutex<Option<std::os::unix::io::RawFd>>>,
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

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
pub struct NetworkInterfaceInfo {
    pub ip: String,
    pub interface_name: String,
}

const VIRTUAL_KEYWORDS: &[&str] = &[
    "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
    "tunnel", "teredo", "isatap", "vpn", "tailscale", "clash", "flclash",
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
        score_b.cmp(&score_a)
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
