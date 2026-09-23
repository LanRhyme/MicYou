/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

use log;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use micyou_protocol::MDNS_SERVICE_TYPE;
use std::collections::HashMap;

pub struct NetworkManager {
    mdns: ServiceDaemon,
    service_fullname: String,
}

/// Interface-name filter for IPv6 advertisement, mirroring the virtual
/// interface exclusions `get_best_ip` applies for IPv4.
const V6_EXCLUDE_KEYWORDS: &[&str] = &["tailscale", "virtual", "wsl", "veth", "flclash", "clash"];

impl NetworkManager {
    pub fn start_mdns(port: u16, bind_address: &str) -> Result<Self, Box<dyn std::error::Error>> {
        Self::start_mdns_helper(bind_address, port, MDNS_SERVICE_TYPE)
    }

    pub fn start_web_mdns(
        port: u16,
        bind_address: &str,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        Self::start_mdns_helper(bind_address, port, micyou_protocol::MDNS_WEB_SERVICE_TYPE)
    }

    pub fn stop_mdns(&self) {
        let _ = self.mdns.unregister(&self.service_fullname);
        let _ = self.mdns.shutdown();
    }

    fn get_best_ip() -> Option<String> {
        if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
            let mut best_ip = None;
            for (name, ip) in interfaces {
                if ip.is_loopback() || !ip.is_ipv4() {
                    continue;
                }
                let ip_str = ip.to_string();
                let name_lower = name.to_lowercase();

                // Filter out common TUN/VPN and virtual interfaces
                if ip_str.starts_with("198.18.")
                    || name_lower.contains("tailscale")
                    || name_lower.contains("virtual")
                    || name_lower.contains("wsl")
                    || name_lower.contains("veth")
                    || name_lower.contains("flclash")
                    || name_lower.contains("clash")
                {
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

    /// Resolves which address to advertise in mDNS for a configured bind
    /// address. mdns-sd turns IPv4 literals into A records and IPv6 literals
    /// into AAAA records; a comma-separated pair registers both.
    ///
    /// - `"0.0.0.0"` (legacy IPv4 auto-bind): best IPv4 — and, because the
    ///   servers now also bind an IPv6 companion socket in this mode, the
    ///   best IPv6 as an additional AAAA record when the host has one. On
    ///   IPv4-only hosts the record is byte-identical to the legacy one.
    /// - `"::"` / `"[::]"` (dual-stack bind): best IPv6, falling back to the
    ///   best IPv4 when the host has no usable IPv6 address.
    /// - a specific address: that address, with brackets stripped so IPv6
    ///   literals stay in the bare form mdns-sd expects.
    fn mdns_advertise_ip(bind_address: &str) -> String {
        if bind_address == "0.0.0.0" {
            let v4 = Self::get_best_ip().unwrap_or_else(|| "127.0.0.1".to_string());
            return match crate::net_bind::best_ipv6(V6_EXCLUDE_KEYWORDS) {
                Some(v6) => format!("{},{}", v4, v6),
                None => v4,
            };
        }
        if crate::net_bind::is_unspecified_v6(bind_address) {
            if let Some(ip) = crate::net_bind::best_ipv6(V6_EXCLUDE_KEYWORDS) {
                return ip;
            }
            return Self::get_best_ip().unwrap_or_else(|| "::1".to_string());
        }
        crate::net_bind::strip_brackets(bind_address).to_string()
    }

    fn start_mdns_helper(
        bind_address: &str,
        port: u16,
        service_type: &str,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;

        let host_name = hostname::get()?
            .into_string()
            .unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("MicYou ({})", host_name);

        let local_ip = Self::mdns_advertise_ip(bind_address);

        let service_fullname = format!("{}.{}", instance_name, service_type);

        // Hostname must be a valid DNS name, e.g. "mycomputer.local."
        let valid_host_name = format!("{}.local.", host_name.replace(" ", "-"));

        // Setup mDNS service info
        let properties: HashMap<String, String> = HashMap::new();
        let service_info = ServiceInfo::new(
            service_type,
            &instance_name,
            &valid_host_name,
            local_ip.to_string(),
            port,
            Some(properties),
        )?;

        // Register the service
        mdns.register(service_info)?;
        log::info!("mDNS Service registered: {}", service_fullname);

        Ok(Self {
            mdns,
            service_fullname,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::NetworkManager;

    #[test]
    fn advertise_ip_for_auto_bind_is_ip_list() {
        // Either a single IPv4 (legacy) or "v4,v6" when the host has IPv6;
        // every comma-separated part must be a parseable address that mdns-sd
        // can register.
        let addrs = NetworkManager::mdns_advertise_ip("0.0.0.0");
        assert!(!addrs.is_empty());
        for part in addrs.split(',') {
            assert!(
                part.parse::<std::net::IpAddr>().is_ok(),
                "unparseable mDNS address part: {:?}",
                part
            );
        }
    }

    #[test]
    fn advertise_ip_passes_specific_addresses_through() {
        assert_eq!(
            NetworkManager::mdns_advertise_ip("192.168.1.5"),
            "192.168.1.5"
        );
        assert_eq!(NetworkManager::mdns_advertise_ip("fd00::1"), "fd00::1");
        // Brackets are stripped so mdns-sd sees the bare literal.
        assert_eq!(NetworkManager::mdns_advertise_ip("[fd00::1]"), "fd00::1");
    }

    #[test]
    fn advertise_ip_for_unspecified_v6_is_never_unspecified() {
        // "::" itself must never end up in a service record; it resolves to
        // the best IPv6 or falls back to the best IPv4.
        let addrs = NetworkManager::mdns_advertise_ip("::");
        assert_ne!(addrs, "::");
        assert_ne!(addrs, "[::]");
        assert!(addrs.parse::<std::net::IpAddr>().is_ok());
    }
}
