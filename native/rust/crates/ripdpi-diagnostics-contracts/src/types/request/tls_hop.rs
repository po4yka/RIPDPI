use serde::{Deserialize, Serialize};
use std::net::IpAddr;

/// One user-selected endpoint (port 443) probed with a control SNI and a test SNI.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TlsHopComparisonConfig {
    pub host: String,
    pub control_sni: String,
    pub test_sni: String,
}

impl TlsHopComparisonConfig {
    pub const PORT: u16 = 443;

    pub fn validate(&self) -> Result<(), &'static str> {
        let valid_host = |host: &str| super::Http3ProbeConfig { host: host.into(), ..Default::default() }.validate();
        // An IP literal sends no SNI, so both names must be DNS names.
        let valid_sni = |sni: &str| valid_host(sni).is_ok() && sni.parse::<IpAddr>().is_err();
        if valid_host(&self.host).is_err()
            || !valid_sni(&self.control_sni)
            || !valid_sni(&self.test_sni)
            || self.control_sni.eq_ignore_ascii_case(&self.test_sni)
        {
            return Err("invalid_config");
        }
        Ok(())
    }
}

/// Exported summary of one hop comparison run. Per-attempt rows travel in [`TlsHopComparisonLocal`] only.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TlsHopComparisonEvidence {
    /// `DIVERGENCE`, `INCONCLUSIVE`, `UNSUPPORTED` or `CANCELLED`.
    pub status: String,
    /// Why the run is unsupported or inconclusive without a ladder, for example `proxy_path` or `ttl_setter`.
    pub reason: Option<String>,
    pub first_hop: Option<u8>,
    pub last_hop: Option<u8>,
    pub path_length: Option<u8>,
    pub limits: Vec<String>,
    /// `COMPLETED`, `CANCELLED`, `DEADLINE_EXCEEDED`, `NETWORK_CHANGED` or `UNSUPPORTED`.
    pub stop: Option<String>,
    pub control_attempts: u32,
    pub test_attempts: u32,
}

/// Local-history detail: the SNIs and per-attempt timings. The ordinary export drops it.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TlsHopComparisonLocal {
    pub control_sni: String,
    pub test_sni: String,
    pub attempts: Vec<TlsHopAttempt>,
}

impl TlsHopComparisonEvidence {
    pub fn to_detail_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn from_detail_json(value: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(value)
    }
}

impl TlsHopComparisonLocal {
    pub fn to_detail_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TlsHopAttempt {
    pub ttl: u8,
    /// `CONTROL` or `TEST`.
    pub role: String,
    pub repetition: u8,
    /// `RST`, `FIN`, `TLS_ALERT`, `SERVER_HELLO`, `OTHER`, `SILENCE`, `HANDSHAKE_FAILED` or `LOCAL_ERROR`.
    pub outcome: String,
    pub elapsed_ms: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid() -> TlsHopComparisonConfig {
        TlsHopComparisonConfig {
            host: "203.0.113.7".into(),
            control_sni: "control.example".into(),
            test_sni: "test.example".into(),
        }
    }

    #[test]
    fn validates_endpoint_and_distinct_dns_snis() {
        assert!(valid().validate().is_ok());
        assert!(TlsHopComparisonConfig { host: "endpoint.example".into(), ..valid() }.validate().is_ok());
        for config in [
            TlsHopComparisonConfig { host: String::new(), ..valid() },
            TlsHopComparisonConfig { host: "user@host".into(), ..valid() },
            TlsHopComparisonConfig { control_sni: "192.0.2.1".into(), ..valid() },
            TlsHopComparisonConfig { test_sni: "a..b".into(), ..valid() },
            TlsHopComparisonConfig { test_sni: "CONTROL.example".into(), ..valid() },
            TlsHopComparisonConfig::default(),
        ] {
            assert!(config.validate().is_err(), "{config:?}");
        }
    }
}
