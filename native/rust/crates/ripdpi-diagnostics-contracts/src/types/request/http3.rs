use serde::{Deserialize, Serialize};
use std::net::IpAddr;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Http3ProbeConfig {
    pub version: u32,
    pub host: String,
    pub port: u16,
    pub path: String,
    pub connect_ip: Option<String>,
    pub timeout_ms: u64,
    pub max_response_bytes: u64,
}
impl Default for Http3ProbeConfig {
    fn default() -> Self {
        Self {
            version: 1,
            host: "www.cloudflare.com".into(),
            port: 443,
            path: "/cdn-cgi/trace".into(),
            connect_ip: None,
            timeout_ms: 5000,
            max_response_bytes: 65536,
        }
    }
}
impl Http3ProbeConfig {
    pub fn validate(&self) -> Result<(), &'static str> {
        let valid_host = self.host.parse::<IpAddr>().is_ok()
            || (!self.host.is_empty()
                && self.host.len() <= 253
                && self.host.split('.').all(|label| {
                    !label.is_empty()
                        && label.len() <= 63
                        && label.as_bytes().first().is_some_and(u8::is_ascii_alphanumeric)
                        && label.as_bytes().last().is_some_and(u8::is_ascii_alphanumeric)
                        && label.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-')
                }));
        if self.version != 1
            || self.port == 0
            || !(250..=15000).contains(&self.timeout_ms)
            || !(1..=262144).contains(&self.max_response_bytes)
            || !valid_host
            || self.connect_ip.as_ref().is_some_and(|value| value.parse::<IpAddr>().is_err())
            || !self.path.starts_with('/')
            || self.path.starts_with("//")
            || self.path.len() > 1024
            || self.path.bytes().any(|c| {
                !c.is_ascii() || c.is_ascii_control() || c.is_ascii_whitespace() || matches!(c, b'?' | b'#' | b'\\')
            })
        {
            return Err("invalid_config");
        }
        Ok(())
    }
}

/// Bounded protocol observations. Never contains header values or body data.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Http3Evidence {
    pub version: u32,
    pub stage: String,
    pub status: String,
    pub dns_status: String,
    pub reason: Option<String>,
    pub peer_address: Option<String>,
    pub alpn: Option<String>,
    pub tls_validated: bool,
    pub http3_validated: bool,
    pub request_sent: bool,
    pub http_status: Option<u16>,
    pub response_bytes: u64,
    pub body_complete: bool,
    pub attempt_count: u32,
    pub duration_ms: Option<u64>,
    pub handshake_elapsed_ms: Option<u64>,
    pub headers_elapsed_ms: Option<u64>,
    pub first_byte_elapsed_ms: Option<u64>,
    pub path_scope: String,
}
impl Default for Http3Evidence {
    fn default() -> Self {
        Self {
            version: 1,
            stage: "DNS".into(),
            status: "NOT_OBSERVED".into(),
            dns_status: "NOT_RUN".into(),
            reason: None,
            peer_address: None,
            alpn: None,
            tls_validated: false,
            http3_validated: false,
            request_sent: false,
            http_status: None,
            response_bytes: 0,
            body_complete: false,
            attempt_count: 0,
            duration_ms: None,
            handshake_elapsed_ms: None,
            headers_elapsed_ms: None,
            first_byte_elapsed_ms: None,
            path_scope: "RAW_PATH".into(),
        }
    }
}
impl Http3Evidence {
    pub fn to_detail_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_and_invalid_authorities() {
        let config: Http3ProbeConfig = serde_json::from_str("{}").unwrap();
        assert!(config.validate().is_ok());
        for host in ["", "user@host", "a b", "a/b", "-bad.example", "a..b"] {
            assert!(Http3ProbeConfig { host: host.into(), ..config.clone() }.validate().is_err());
        }
        for path in ["https://example.org", "//example.org", "/?secret", "/#fragment", "/bad\r\n"] {
            assert!(Http3ProbeConfig { path: path.into(), ..config.clone() }.validate().is_err());
        }
    }
}
