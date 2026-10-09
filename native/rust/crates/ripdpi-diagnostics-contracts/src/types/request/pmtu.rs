use serde::{Deserialize, Serialize};
use std::net::IpAddr;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PmtuProbeConfig {
    pub version: u32,
    pub host: String,
    pub port: u16,
    pub connect_ipv4: Option<String>,
    pub connect_ipv6: Option<String>,
    pub timeout_ms: u64,
    pub observation_ms: u64,
    pub upper_bound_udp_payload_bytes: u16,
}
impl Default for PmtuProbeConfig {
    fn default() -> Self {
        Self {
            version: 1,
            host: "www.cloudflare.com".into(),
            port: 443,
            connect_ipv4: None,
            connect_ipv6: None,
            timeout_ms: 10000,
            observation_ms: 3000,
            upper_bound_udp_payload_bytes: 1472,
        }
    }
}
impl PmtuProbeConfig {
    pub fn validate(&self) -> Result<(), &'static str> {
        let host = super::Http3ProbeConfig { host: self.host.clone(), ..Default::default() };
        if self.version != 1
            || self.port == 0
            || host.validate().is_err()
            || matches!(self.host.parse::<IpAddr>(), Ok(IpAddr::V6(ip)) if ip.to_ipv4_mapped().is_some())
            || !(250..=15000).contains(&self.timeout_ms)
            || !(250..=10000).contains(&self.observation_ms)
            || self.observation_ms > self.timeout_ms
            || !(1201..=1472).contains(&self.upper_bound_udp_payload_bytes)
            || self.connect_ipv4.as_ref().is_some_and(|s| !matches!(s.parse::<IpAddr>(), Ok(IpAddr::V4(_))))
            || self
                .connect_ipv6
                .as_ref()
                .is_some_and(|s| !matches!(s.parse::<IpAddr>(), Ok(IpAddr::V6(ip)) if ip.to_ipv4_mapped().is_none()))
        {
            return Err("invalid_config");
        }
        Ok(())
    }
}
/// Authenticated UDP payload observations; never an exact IP path MTU.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PmtuEvidence {
    pub version: u32,
    pub method: String,
    pub address_family: String,
    pub status: String,
    pub reason: Option<String>,
    pub peer_address: Option<String>,
    pub tls_validated: bool,
    pub alpn: Option<String>,
    pub fragmentation_prevented: bool,
    pub initial_udp_payload_bytes: u16,
    pub configured_upper_bound_udp_payload_bytes: u16,
    pub current_udp_payload_bytes: Option<u16>,
    pub acknowledged_udp_payload_lower_bound_bytes: Option<u16>,
    pub sent_probe_count: u64,
    pub lost_probe_count: u64,
    pub black_hole_count: u64,
    pub observation_window_complete: bool,
    pub configured_upper_bound_reached: bool,
    pub duration_ms: Option<u64>,
    pub handshake_elapsed_ms: Option<u64>,
    pub path_scope: String,
}
impl Default for PmtuEvidence {
    fn default() -> Self {
        Self {
            version: 1,
            method: "QUIC_DPLPMTUD".into(),
            address_family: "IPV4".into(),
            status: "NOT_OBSERVED".into(),
            reason: None,
            peer_address: None,
            tls_validated: false,
            alpn: None,
            fragmentation_prevented: false,
            initial_udp_payload_bytes: 1200,
            configured_upper_bound_udp_payload_bytes: 1472,
            current_udp_payload_bytes: None,
            acknowledged_udp_payload_lower_bound_bytes: None,
            sent_probe_count: 0,
            lost_probe_count: 0,
            black_hole_count: 0,
            observation_window_complete: false,
            configured_upper_bound_reached: false,
            duration_ms: None,
            handshake_elapsed_ms: None,
            path_scope: "RAW_PATH".into(),
        }
    }
}
impl PmtuEvidence {
    pub fn to_detail_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_budgets_and_family_pins() {
        let c: PmtuProbeConfig = serde_json::from_str("{}").unwrap();
        assert!(c.validate().is_ok());
        for c in [
            PmtuProbeConfig { connect_ipv4: Some("::1".into()), ..c.clone() },
            PmtuProbeConfig { connect_ipv6: Some("127.0.0.1".into()), ..c.clone() },
            PmtuProbeConfig { connect_ipv6: Some("::ffff:192.0.2.1".into()), ..c.clone() },
            PmtuProbeConfig { host: "::ffff:192.0.2.1".into(), ..c.clone() },
            PmtuProbeConfig { observation_ms: 10001, ..c.clone() },
            PmtuProbeConfig { timeout_ms: 250, observation_ms: 251, ..c.clone() },
            PmtuProbeConfig { upper_bound_udp_payload_bytes: 1200, ..c.clone() },
            PmtuProbeConfig { host: "user@host".into(), ..c.clone() },
        ] {
            assert!(c.validate().is_err());
        }
    }
}
