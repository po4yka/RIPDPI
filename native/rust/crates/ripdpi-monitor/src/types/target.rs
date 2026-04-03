use serde::{Deserialize, Serialize};

use super::strategy::StrategyProbeTargetSelection;
use crate::util::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DomainTarget {
    pub host: String,
    #[serde(default)]
    pub connect_ip: Option<String>,
    #[serde(default)]
    pub https_port: Option<u16>,
    #[serde(default)]
    pub http_port: Option<u16>,
    #[serde(default = "default_http_path")]
    pub http_path: String,
    #[serde(default)]
    pub is_control: bool,
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
    pub port: Option<u16>,
    #[serde(default)]
    pub is_control: bool,
    #[serde(default = "default_throughput_window_bytes")]
    pub window_bytes: usize,
    #[serde(default = "default_throughput_runs")]
    pub runs: usize,
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
    }
}
