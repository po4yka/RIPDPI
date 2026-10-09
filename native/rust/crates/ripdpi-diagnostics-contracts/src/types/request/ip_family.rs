use serde::{Deserialize, Serialize};
use std::net::{Ipv4Addr, Ipv6Addr};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct IpFamilyProbeConfig {
    pub version: u32,
    pub ipv4_address: String,
    pub ipv6_address: String,
    pub port: u16,
    pub timeout_ms: u64,
}
impl Default for IpFamilyProbeConfig {
    fn default() -> Self {
        Self {
            version: 1,
            ipv4_address: "1.1.1.1".into(),
            ipv6_address: "2606:4700:4700::1111".into(),
            port: 443,
            timeout_ms: 1500,
        }
    }
}
impl IpFamilyProbeConfig {
    pub fn validate(&self) -> Result<(), &'static str> {
        let v4 = self.ipv4_address.parse::<Ipv4Addr>().map_err(|_| "invalid_config")?;
        let v6 = self.ipv6_address.parse::<Ipv6Addr>().map_err(|_| "invalid_config")?;
        if self.version != 1
            || self.port == 0
            || !(100..=5000).contains(&self.timeout_ms)
            || v4.is_unspecified()
            || v4.is_multicast()
            || v4.is_broadcast()
            || matches!(v4.octets(), [192, 0, 0, 170 | 171])
            || v6.is_unspecified()
            || v6.is_multicast()
            || v6.to_ipv4_mapped().is_some()
        {
            return Err("invalid_config");
        }
        Ok(())
    }
}

/// Bounded probe-owned facts. Addresses are removed from redacted exports.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IpFamilyEvidence {
    pub version: u32,
    pub family: String,
    pub stage: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub destination_address: Option<String>,
    pub destination_port: u16,
    pub attempt_count: u32,
    pub path_scope: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prefix: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prefix_length: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub discovery_status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resolver_source: Option<String>,
}
impl IpFamilyEvidence {
    pub fn to_detail_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn empty_config_uses_same_defaults_as_kotlin() {
        let config: IpFamilyProbeConfig = serde_json::from_str("{}").unwrap();
        assert_eq!(config.version, 1);
        assert_eq!(config.port, 443);
        assert_eq!(config.timeout_ms, 1500);
        assert_eq!(config.ipv4_address, "1.1.1.1");
        assert_eq!(config.ipv6_address, "2606:4700:4700::1111");
        assert!(config.validate().is_ok());
    }
    #[test]
    fn rejects_mixed_family_destinations_and_discovery_sentinels() {
        let mut config = IpFamilyProbeConfig {
            version: 1,
            ipv4_address: "1.1.1.1".into(),
            ipv6_address: "2606:4700:4700::1111".into(),
            port: 443,
            timeout_ms: 1500,
        };
        assert!(config.validate().is_ok());
        for bad in ["192.0.0.170", "192.0.0.171", "::1", "example.org", "0.0.0.0"] {
            config.ipv4_address = bad.into();
            assert!(config.validate().is_err());
        }
        config.ipv4_address = "1.1.1.1".into();
        for bad in ["1.1.1.1", "::ffff:1.1.1.1", "::", "example.org"] {
            config.ipv6_address = bad.into();
            assert!(config.validate().is_err());
        }
    }
}
