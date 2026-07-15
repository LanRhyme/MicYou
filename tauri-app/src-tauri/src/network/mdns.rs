use log;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use micyou_protocol::MDNS_SERVICE_TYPE;
use std::collections::HashMap;

pub struct NetworkManager {
    mdns: ServiceDaemon,
    service_fullname: String,
}

impl NetworkManager {
    pub fn start_mdns(port: u16, bind_address: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;

        let host_name = hostname::get()?
            .into_string()
            .unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("MicYou ({})", host_name);

        let local_ip = if bind_address == "0.0.0.0" {
            super::interface::best_lan_ip().unwrap_or_else(|| "127.0.0.1".to_string())
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
        log::info!(target: "mdns", "mDNS Service registered: {}", service_fullname);

        Ok(Self {
            mdns,
            service_fullname,
        })
    }

    pub fn stop_mdns(&self) {
        let _ = self.mdns.unregister(&self.service_fullname);
        let _ = self.mdns.shutdown();
    }

    pub fn start_web_mdns(
        port: u16,
        bind_address: &str,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;
        let host_name = hostname::get()?
            .into_string()
            .unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("MicYou Web ({})", host_name);
        let local_ip = if bind_address == "0.0.0.0" {
            super::interface::best_lan_ip().unwrap_or_else(|| "127.0.0.1".to_string())
        } else {
            bind_address.to_string()
        };
        let service_fullname = format!(
            "{}.{}",
            instance_name,
            micyou_protocol::MDNS_WEB_SERVICE_TYPE
        );
        let valid_host_name = format!("{}.local.", host_name.replace(" ", "-"));
        let properties: HashMap<String, String> = HashMap::new();
        let service_info = ServiceInfo::new(
            micyou_protocol::MDNS_WEB_SERVICE_TYPE,
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
}
