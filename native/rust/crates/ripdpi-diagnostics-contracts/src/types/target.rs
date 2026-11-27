use serde::{Deserialize, Serialize};

use super::strategy::StrategyProbeTargetSelection;
use crate::util::*;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConcurrencyProbeTargetMetadata {
    pub cohort_id: String,
    pub max_parallelism: u16,
}

/// Where a control lives, so one blocked provider or a broken international link is not read as a
/// broken network. Domestic controls answer on any working local network and never prove a bypass path.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ControlTier {
    Domestic,
    Global,
    Cdn,
}

impl ControlTier {
    pub fn wire_name(self) -> &'static str {
        match self {
            Self::Domestic => "domestic",
            Self::Global => "global",
            Self::Cdn => "cdn",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DomainTarget {
    pub host: String,
    #[serde(default)]
    pub connect_ip: Option<String>,
    #[serde(default)]
    pub connect_ips: Vec<String>,
    #[serde(default)]
    pub https_port: Option<u16>,
    #[serde(default)]
    pub http_port: Option<u16>,
    #[serde(default = "default_http_path")]
    pub http_path: String,
    #[serde(default)]
    pub is_control: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub control_tier: Option<ControlTier>,
    /// Presence explicitly opts this safe catalog target into the bounded
    /// same-SNI connection-concurrency matrix.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub concurrency_probe: Option<ConcurrencyProbeTargetMetadata>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DnsTarget {
    pub domain: String,
    #[serde(default)]
    pub udp_server: Option<String>,
    #[serde(default)]
    pub encrypted_resolver_id: Option<String>,
    #[serde(default)]
    pub encrypted_protocol: Option<String>,
    #[serde(default)]
    pub encrypted_host: Option<String>,
    #[serde(default)]
    pub encrypted_port: Option<u16>,
    #[serde(default)]
    pub encrypted_tls_server_name: Option<String>,
    #[serde(default)]
    pub encrypted_bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub encrypted_doh_url: Option<String>,
    #[serde(default)]
    pub encrypted_dnscrypt_provider_name: Option<String>,
    #[serde(default)]
    pub encrypted_dnscrypt_public_key: Option<String>,
    #[serde(default)]
    pub expected_ips: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TcpTarget {
    pub id: String,
    pub provider: String,
    pub ip: String,
    pub port: u16,
    pub sni: Option<String>,
    pub asn: Option<String>,
    #[serde(default)]
    pub host_header: Option<String>,
    #[serde(default)]
    pub fat_header_requests: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt_port: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuicTarget {
    pub host: String,
    #[serde(default)]
    pub connect_ip: Option<String>,
    #[serde(default)]
    pub connect_ips: Vec<String>,
    #[serde(default = "default_quic_port")]
    pub port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelegramDcEndpoint {
    pub ip: String,
    pub label: String,
    #[serde(default = "default_telegram_dc_port")]
    pub port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelegramTarget {
    pub media_url: String,
    pub upload_ip: String,
    #[serde(default = "default_telegram_dc_port")]
    pub upload_port: u16,
    pub dc_endpoints: Vec<TelegramDcEndpoint>,
    #[serde(default = "default_telegram_stall_timeout_ms")]
    pub stall_timeout_ms: u64,
    #[serde(default = "default_telegram_total_timeout_ms")]
    pub total_timeout_ms: u64,
    #[serde(default = "default_telegram_upload_size")]
    pub upload_size_bytes: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeRequest {
    #[serde(default = "default_strategy_probe_suite")]
    pub suite_id: String,
    #[serde(default)]
    pub base_proxy_config_json: Option<String>,
    #[serde(default)]
    pub target_selection: Option<StrategyProbeTargetSelection>,
    /// When set, limits the strategy probe to at most this many candidates
    /// (including baseline). Used by quick scan mode.
    #[serde(default)]
    pub max_candidates: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServiceTarget {
    pub id: String,
    pub service: String,
    #[serde(default)]
    pub bootstrap_url: Option<String>,
    #[serde(default)]
    pub media_url: Option<String>,
    #[serde(default)]
    pub tcp_endpoint_host: Option<String>,
    #[serde(default)]
    pub tcp_endpoint_ip: Option<String>,
    #[serde(default = "default_quic_port")]
    pub tcp_endpoint_port: u16,
    #[serde(default)]
    pub tls_server_name: Option<String>,
    #[serde(default)]
    pub quic_host: Option<String>,
    #[serde(default)]
    pub quic_connect_ip: Option<String>,
    #[serde(default = "default_quic_port")]
    pub quic_port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CircumventionTarget {
    pub id: String,
    pub tool: String,
    #[serde(default)]
    pub bootstrap_url: Option<String>,
    #[serde(default)]
    pub handshake_host: Option<String>,
    #[serde(default)]
    pub handshake_ip: Option<String>,
    #[serde(default = "default_quic_port")]
    pub handshake_port: u16,
    #[serde(default)]
    pub tls_server_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThroughputTarget {
    pub id: String,
    pub label: String,
    pub url: String,
    #[serde(default)]
    pub connect_ip: Option<String>,
    #[serde(default)]
    pub connect_ips: Vec<String>,
    #[serde(default)]
    pub port: Option<u16>,
    #[serde(default)]
    pub is_control: bool,
    #[serde(default = "default_throughput_window_bytes")]
    pub window_bytes: usize,
    #[serde(default = "default_throughput_runs")]
    pub runs: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verification: Option<DownloadVerification>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub upload_body_bytes: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_class: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub destination_asn: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DownloadVerification {
    pub expected_body_bytes: usize,
    pub sha256: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ech_comparison_ip: Option<std::net::IpAddr>,
}

#[derive(Deserialize)]
pub struct VerifiedUploadReceipt {
    pub bytes: u64,
    pub sha256: String,
}

impl VerifiedUploadReceipt {
    pub const MAX_BYTES: usize = 512;

    pub fn from_json(body: &[u8]) -> Result<Self, &'static str> {
        if body.len() > Self::MAX_BYTES {
            return Err("receipt_too_large");
        }
        let receipt: Self = serde_json::from_slice(body).map_err(|_| "invalid_receipt")?;
        if receipt.sha256.len() != 64 || !receipt.sha256.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err("invalid_receipt");
        }
        Ok(receipt)
    }
}

pub const VERIFIED_DOWNLOAD_PROFILE_ID: &str = "verified-https-download";
pub const VERIFIED_UPLOAD_PROFILE_ID: &str = "verified-https-upload";
pub const ECH_TRANSFER_COMPARISON_PROFILE_ID: &str = "ech-transfer-comparison";
pub const ECH_TRANSFER_COMPARISON_LEGS: usize = 3;
pub const HTTP3_TRANSFER_COMPARISON_PROFILE_ID: &str = "http3-transfer-comparison";
pub const HTTP3_TRANSFER_COMPARISON_LEGS: usize = 2;
pub const TRANSFER_VOLUME_PROFILE_ID: &str = "transfer-volume-comparison";
pub const TRANSFER_VOLUME_SHORT_ID: &str = "transfer-volume-short";
pub const TRANSFER_VOLUME_LONG_ID: &str = "transfer-volume-long";
pub const MAX_TRANSFER_VOLUME_SHORT_BYTES: usize = 32 * 1024;
pub const MAX_HTTP3_COMPARISON_BODY_BYTES: usize = 32 * 1024;
pub const MAX_ECH_COMPARISON_BODY_BYTES: usize = 32 * 1024;
pub const MAX_VERIFIED_DOWNLOAD_BYTES: usize = 1024 * 1024;
pub const MAX_VERIFIED_UPLOAD_BYTES: usize = 1024 * 1024;

impl ThroughputTarget {
    pub fn transfer_volume_leg(&self) -> Option<&'static str> {
        match self.id.as_str() {
            TRANSFER_VOLUME_SHORT_ID => Some("short"),
            TRANSFER_VOLUME_LONG_ID => Some("long"),
            _ => None,
        }
    }

    pub fn pinned_download_ip(&self) -> Option<std::net::IpAddr> {
        self.connect_ip.as_deref()?.parse::<std::net::IpAddr>().ok().map(|ip| ip.to_canonical()).filter(|ip| {
            !ip.is_unspecified() && !ip.is_multicast() && *ip != std::net::IpAddr::V4(std::net::Ipv4Addr::BROADCAST)
        })
    }

    /// Validate the verification request before any socket is opened.
    pub fn validate_download(&self) -> Result<(), &'static str> {
        let Some(expected) = &self.verification else {
            return Ok(());
        };
        let http3_comparison = self.id == HTTP3_TRANSFER_COMPARISON_PROFILE_ID;
        let volume_comparison = self.transfer_volume_leg().is_some();
        if !self.has_verified_https_endpoint(http3_comparison || volume_comparison) {
            return Err("invalid verified download URL");
        }
        let max_bytes = if http3_comparison {
            if expected.ech_comparison_ip.is_some() || !self.has_pinned_download_endpoint() {
                return Err("HTTP/3 comparison requires one fixed IP and HTTPS path");
            }
            MAX_HTTP3_COMPARISON_BODY_BYTES
        } else if volume_comparison {
            if expected.ech_comparison_ip.is_some() || !self.has_pinned_download_endpoint() {
                return Err("volume comparison requires a fixed IP and HTTPS path");
            }
            if self.id == TRANSFER_VOLUME_SHORT_ID {
                MAX_TRANSFER_VOLUME_SHORT_BYTES
            } else {
                MAX_VERIFIED_DOWNLOAD_BYTES
            }
        } else if let Some(ip) = expected.ech_comparison_ip {
            let ip = ip.to_canonical();
            if ip.is_unspecified() || ip.is_multicast() {
                return Err("ECH comparison requires a unicast destination");
            }
            if !has_ech_comparison_authority(&self.url) {
                return Err("ECH comparison requires a DNS origin");
            }
            MAX_ECH_COMPARISON_BODY_BYTES
        } else {
            MAX_VERIFIED_DOWNLOAD_BYTES
        };
        if !(1..=max_bytes).contains(&expected.expected_body_bytes)
            || self.window_bytes != expected.expected_body_bytes + 1
            || self.runs != 1
        {
            return Err("invalid verified download size or run count");
        }
        if expected.sha256.len() != 64 || !expected.sha256.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err("verified download requires a SHA-256 digest");
        }
        Ok(())
    }

    /// Validate the upload request before any socket is opened.
    pub fn validate_upload(&self) -> Result<(), &'static str> {
        let Some(bytes) = self.upload_body_bytes else {
            return Ok(());
        };
        if !self.has_verified_https_endpoint(false) {
            return Err("invalid verified upload URL");
        }
        if !(1..=MAX_VERIFIED_UPLOAD_BYTES).contains(&bytes)
            || self.window_bytes != bytes
            || self.runs != 1
            || self.verification.is_some()
        {
            return Err("invalid verified upload size, run count, or mode");
        }
        Ok(())
    }

    fn has_pinned_download_endpoint(&self) -> bool {
        let path = self.url.strip_prefix("https://").and_then(|url| url.find('/').map(|start| &url[start..]));
        self.pinned_download_ip().is_some()
            && path.is_none_or(|value| value.is_ascii() && value.len() <= 1024 && !value.starts_with("//"))
    }

    fn has_verified_https_endpoint(&self, allow_pin: bool) -> bool {
        let Some(url) = self.url.strip_prefix("https://") else {
            return false;
        };
        let authority = url.split('/').next().unwrap_or_default();
        !authority.is_empty()
            && !authority.contains('@')
            && self.url.len() <= 2048
            && !self.url.contains(['?', '#', '\\'])
            && !self.url.bytes().any(|byte| byte.is_ascii_whitespace() || byte.is_ascii_control())
            && (allow_pin || self.connect_ip.is_none())
            && self.connect_ips.is_empty()
            && self.port.is_none()
    }
}

fn has_ech_comparison_authority(url: &str) -> bool {
    let authority = url.strip_prefix("https://").and_then(|suffix| suffix.split('/').next()).unwrap_or_default();
    let (host, port) = authority.split_once(':').map_or((authority, None), |(host, port)| (host, Some(port)));
    let name = host.strip_suffix('.').unwrap_or(host);
    !name.is_empty()
        && name.len() <= 253
        && name.parse::<std::net::IpAddr>().is_err()
        && name.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
        && port.is_none_or(|value| value.parse::<u16>().is_ok_and(|parsed| parsed > 0))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnosis {
    pub code: String,
    pub summary: String,
    #[serde(default = "default_diagnosis_severity")]
    pub severity: String,
    #[serde(default)]
    pub target: Option<String>,
    #[serde(default)]
    pub evidence: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recommendation: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub control_validated: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_target_defaults_port_to_443() {
        let json = r#"{"host": "example.com"}"#;
        let target: QuicTarget = serde_json::from_str(json).expect("deserialize");
        assert_eq!(target.port, 443);
        assert!(target.connect_ips.is_empty());
    }

    #[test]
    fn strategy_probe_request_defaults_suite() {
        let json = r#"{}"#;
        let req: StrategyProbeRequest = serde_json::from_str(json).expect("deserialize");
        assert_eq!(req.suite_id, "quick_v1");
        assert!(req.target_selection.is_none());
    }

    #[test]
    fn throughput_target_defaults_window_and_runs() {
        let json = r#"{
            "id": "youtube",
            "label": "YouTube",
            "url": "https://www.youtube.com/"
        }"#;
        let target: ThroughputTarget = serde_json::from_str(json).expect("deserialize");
        assert_eq!(target.window_bytes, 8 * 1024 * 1024);
        assert_eq!(target.runs, 2);
        assert!(!target.is_control);
        assert!(target.connect_ips.is_empty());
    }

    #[test]
    fn domain_target_accepts_ordered_connect_ips() {
        let json = r#"{
            "host": "example.com",
            "connectIp": "1.1.1.1",
            "connectIps": ["1.1.1.1", "1.0.0.1"]
        }"#;
        let target: DomainTarget = serde_json::from_str(json).expect("deserialize");
        assert_eq!(target.connect_ip.as_deref(), Some("1.1.1.1"));
        assert_eq!(target.connect_ips, vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()]);
    }
}
