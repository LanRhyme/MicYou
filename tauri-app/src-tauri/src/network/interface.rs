use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct NetworkInterfaceInfo {
    pub ip: String,
    pub interface_name: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct NetworkInfo {
    pub ips: Vec<String>,
    pub port: u16,
}

/// Superset of virtual/container/VPN interface keywords from both
/// server.rs and the old network.rs get_best_ip().
const VIRTUAL_KEYWORDS: &[&str] = &[
    "vmware",
    "virtualbox",
    "hyper-v",
    "vethernet",
    "wsl",
    "docker",
    "tunnel",
    "teredo",
    "isatap",
    "vpn",
    "tailscale",
    "clash",
    "flclash",
    "virtual",
    "veth",
];

/// Score an IPv4 address so 192.168.x.x is preferred, common LAN
/// ranges get moderate scores, and link-local / benchmark ranges
/// are deprioritised.
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

/// Return all non-loopback IPv4 interfaces, filtered against
/// VIRTUAL_KEYWORDS, ranked by `score_ip`.
pub fn list_interfaces() -> Vec<NetworkInterfaceInfo> {
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
        score_b
            .cmp(&score_a)
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

/// Return the single best LAN IP address.
///
/// Strategy (merged from the old `get_best_ip` in network.rs and the
/// score-based ranking in server.rs):
/// 1. Prefer 192.168.x.x (score 100).
/// 2. Fall back to the highest-scoring non-virtual IPv4 interface.
/// 3. Last resort: `local_ip_address::local_ip()`.
pub fn best_lan_ip() -> Option<String> {
    let interfaces = list_interfaces();
    // The first entry is already the highest-ranked one.
    // The fallback "127.0.0.1" entry at index 0 is a loopback address
    // that we specifically want to avoid unless there's really nothing.
    interfaces
        .iter()
        .find(|i| i.ip != "127.0.0.1")
        .map(|i| i.ip.clone())
        .or_else(|| local_ip_address::local_ip().map(|ip| ip.to_string()).ok())
}

/// Check whether an HTTP Origin / Host header belongs to a LAN
/// address (or localhost).  Used by the web server for CORS-like
/// origin validation.
pub fn is_lan_origin(origin: &str) -> bool {
    let o = origin.to_lowercase();
    if o.contains("localhost") || o.contains("127.0.0.1") {
        return true;
    }
    list_interfaces().iter().any(|iface| o.contains(&iface.ip))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_score_ip_prefers_192() {
        assert!(score_ip("192.168.1.1") > score_ip("10.0.0.1"));
        assert!(score_ip("10.0.0.1") > score_ip("198.18.0.1"));
    }

    #[test]
    fn test_score_ip_deprioritises_link_local() {
        assert!(score_ip("169.254.1.1") < 0);
    }

    #[test]
    fn test_list_interfaces_returns_vec() {
        let ifaces = list_interfaces();
        assert!(!ifaces.is_empty(), "Should return at least the fallback");
    }

    #[test]
    fn test_best_lan_ip_returns_some() {
        // In most environments best_lan_ip will return an address;
        // on a CI runner it may fall back to local_ip().
        let ip = best_lan_ip();
        assert!(ip.is_some());
    }

    #[test]
    fn test_is_lan_origin_localhost() {
        assert!(is_lan_origin("http://localhost:8443"));
        assert!(is_lan_origin("https://127.0.0.1"));
    }

    #[test]
    fn test_is_lan_origin_unknown() {
        assert!(!is_lan_origin("https://evil.example.com"));
    }
}
