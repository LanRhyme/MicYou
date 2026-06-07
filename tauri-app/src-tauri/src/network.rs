use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use crate::protocol::{MDNS_SERVICE_TYPE, PORT};

pub struct NetworkManager {
    mdns: ServiceDaemon,
    service_fullname: String,
}

impl NetworkManager {
    pub fn start_mdns(port: u16) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;
        
        let host_name = hostname::get()?.into_string().unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("MicYou ({})", host_name);
        
        let local_ip = local_ip_address::local_ip()?;
        
        let service_fullname = format!("{}.{}", instance_name, MDNS_SERVICE_TYPE);
        
        // Setup mDNS service info
        let properties: HashMap<String, String> = HashMap::new();
        let service_info = ServiceInfo::new(
            MDNS_SERVICE_TYPE,
            &instance_name,
            &service_fullname,
            &local_ip.to_string(),
            port,
            Some(properties)
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
}
