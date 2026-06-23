use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use log;
use crate::protocol::MDNS_SERVICE_TYPE;

pub struct NetworkManager {
    mdns: ServiceDaemon,
    service_fullname: String,
}

impl NetworkManager {
    pub fn start_mdns(port: u16, bind_address: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;

        let host_name = hostname::get()?.into_string().unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("MicYou ({})", host_name);

        let local_ip = if bind_address == "0.0.0.0" {
            Self::get_best_ip().unwrap_or_else(|| "127.0.0.1".to_string())
        } else {
            bind_address.to_string()
        };

        let service_fullname = format!("{}.{}", instance_name, MDNS_SERVICE_TYPE);

        // Hostname must be a valid DNS name, e.g. "mycomputer.local."
        let valid_host_name = format!("{}.local.", host_name.replace(" ", "-"));

        // Setup mDNS service info
        let properties: HashMap<String, String> = HashMap::new();
        let service_info = ServiceInfo::new(
            MDNS_SERVICE_TYPE,
            &instance_name,
            &valid_host_name,
            &local_ip.to_string(),
            port,
            Some(properties),
        )?;

        // Register the service
        mdns.register(service_info)?;
        println!("mDNS Service registered: {}", service_fullname);

        Ok(Self {
            mdns,
            service_fullname,
        })
    }

    pub fn stop_mdns(&self) {
        let _ = self.mdns.unregister(&self.service_fullname);
        let _ = self.mdns.shutdown();
    }

    pub fn start_web_mdns(port: u16, bind_address: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;
        let host_name = hostname::get()?.into_string().unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("MicYou Web ({})", host_name);
        let local_ip = if bind_address == "0.0.0.0" {
            Self::get_best_ip().unwrap_or_else(|| "127.0.0.1".to_string())
        } else {
            bind_address.to_string()
        };
        let service_fullname = format!("{}.{}", instance_name, crate::protocol::MDNS_WEB_SERVICE_TYPE);
        let valid_host_name = format!("{}.local.", host_name.replace(" ", "-"));
        let properties: HashMap<String, String> = HashMap::new();
        let service_info = ServiceInfo::new(
            crate::protocol::MDNS_WEB_SERVICE_TYPE,
            &instance_name,
            &valid_host_name,
            &local_ip.to_string(),
            port,
            Some(properties),
        )?;
        mdns.register(service_info)?;
        log::info!("Web mDNS service registered: {}", service_fullname);
        Ok(Self {
            mdns,
            service_fullname,
        })
    }

    fn get_best_ip() -> Option<String> {
        if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
            let mut best_ip = None;
            for (name, ip) in interfaces {
                if ip.is_loopback() || !ip.is_ipv4() { continue; }
                let ip_str = ip.to_string();
                let name_lower = name.to_lowercase();

                // Filter out common TUN/VPN and virtual interfaces
                if ip_str.starts_with("198.18.") || name_lower.contains("tailscale") || name_lower.contains("virtual") || name_lower.contains("wsl") || name_lower.contains("veth") || name_lower.contains("flclash") || name_lower.contains("clash") {
                    continue;
                }

                if ip_str.starts_with("192.168.") {
                    return Some(ip_str); // Prefer 192.168.x.x
                }
                if best_ip.is_none() {
                    best_ip = Some(ip_str);
                }
            }
            if best_ip.is_some() {
                return best_ip;
            }
        }
        // Fallback
        local_ip_address::local_ip().map(|ip| ip.to_string()).ok()
    }
}
