use std::collections::{BTreeMap, VecDeque};

use ripdpi_proxy_config::NetworkSnapshot;
use serde::{Deserialize, Serialize};

use crate::util::*;

// --- Public API types ---

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanPathMode {
    RawPath,
    InPath,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanKind {
    Connectivity,
    StrategyProbe,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DiagnosticProfileFamily {
    General,
    WebConnectivity,
    Messaging,
    Circumvention,
    Throttling,
    DpiFull,
    AutomaticProbing,
    AutomaticAudit,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ProbeTaskFamily {
    Dns,
    Web,
    Quic,
    Tcp,
    Service,
    Circumvention,
    Telegram,
    Throughput,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeTask {
    pub family: ProbeTaskFamily,
    pub target_id: String,
    pub label: String,
}

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
    pub doh_url: Option<String>,
    #[serde(default)]
    pub doh_bootstrap_ips: Vec<String>,
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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanRequest {
    pub profile_id: String,
    pub display_name: String,
    pub path_mode: ScanPathMode,
    #[serde(default = "default_scan_kind")]
    pub kind: ScanKind,
    #[serde(default = "default_diagnostic_profile_family")]
    pub family: DiagnosticProfileFamily,
    #[serde(default)]
    pub region_tag: Option<String>,
    #[serde(default)]
    pub manual_only: bool,
    #[serde(default)]
    pub pack_refs: Vec<String>,
    pub proxy_host: Option<String>,
    pub proxy_port: Option<u16>,
    #[serde(default)]
    pub probe_tasks: Vec<ProbeTask>,
    pub domain_targets: Vec<DomainTarget>,
    pub dns_targets: Vec<DnsTarget>,
    pub tcp_targets: Vec<TcpTarget>,
    #[serde(default)]
    pub quic_targets: Vec<QuicTarget>,
    #[serde(default)]
    pub service_targets: Vec<ServiceTarget>,
    #[serde(default)]
    pub circumvention_targets: Vec<CircumventionTarget>,
    #[serde(default)]
    pub throughput_targets: Vec<ThroughputTarget>,
    pub whitelist_sni: Vec<String>,
    #[serde(default)]
    pub telegram_target: Option<TelegramTarget>,
    #[serde(default)]
    pub strategy_probe: Option<StrategyProbeRequest>,
    /// Optional OS-level network state snapshot from Android ConnectivityManager/TelephonyManager.
    /// When present, used to short-circuit probes when the OS reports no network, annotate
    /// results with transport context, and emit environment metadata in the scan report.
    #[serde(default)]
    pub network_snapshot: Option<NetworkSnapshot>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanProgress {
    pub session_id: String,
    pub phase: String,
    pub completed_steps: usize,
    pub total_steps: usize,
    pub message: String,
    pub is_finished: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_outcome: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeDetail {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeResult {
    pub probe_type: String,
    pub target: String,
    pub outcome: String,
    pub details: Vec<ProbeDetail>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanReport {
    pub session_id: String,
    pub profile_id: String,
    pub path_mode: ScanPathMode,
    pub started_at: u64,
    pub finished_at: u64,
    pub summary: String,
    pub results: Vec<ProbeResult>,
    #[serde(default)]
    pub diagnoses: Vec<Diagnosis>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classifier_version: Option<String>,
    #[serde(default)]
    pub pack_versions: BTreeMap<String, u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_report: Option<StrategyProbeReport>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeReport {
    pub suite_id: String,
    pub tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub quic_candidates: Vec<StrategyProbeCandidateSummary>,
    pub recommendation: StrategyProbeRecommendation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeCandidateSummary {
    pub id: String,
    pub label: String,
    pub family: String,
    pub outcome: String,
    pub rationale: String,
    pub succeeded_targets: usize,
    pub total_targets: usize,
    pub weighted_success_score: usize,
    pub total_weight: usize,
    pub quality_score: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proxy_config_json: Option<String>,
    #[serde(default)]
    pub notes: Vec<String>,
    pub average_latency_ms: Option<u64>,
    pub skipped: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeRecommendation {
    pub tcp_candidate_id: String,
    pub tcp_candidate_label: String,
    pub quic_candidate_id: String,
    pub quic_candidate_label: String,
    pub rationale: String,
    pub recommended_proxy_config_json: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeSessionEvent {
    pub source: String,
    pub level: String,
    pub message: String,
    pub created_at: u64,
}

// --- Internal shared state ---

#[derive(Default)]
pub(crate) struct SharedState {
    pub(crate) progress: Option<ScanProgress>,
    pub(crate) report: Option<ScanReport>,
    pub(crate) passive_events: VecDeque<NativeSessionEvent>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scan_request_deserializes_with_defaults() {
        let json = r#"{
            "profileId": "test",
            "displayName": "Test",
            "pathMode": "RAW_PATH",
            "proxyHost": null,
            "proxyPort": null,
            "domainTargets": [],
            "dnsTargets": [],
            "tcpTargets": [],
            "whitelistSni": []
        }"#;
        let request: ScanRequest = serde_json::from_str(json).expect("deserialize");
        assert_eq!(request.kind, ScanKind::Connectivity);
        assert_eq!(request.family, DiagnosticProfileFamily::General);
        assert!(request.region_tag.is_none());
        assert!(!request.manual_only);
        assert!(request.pack_refs.is_empty());
        assert!(request.probe_tasks.is_empty());
        assert!(request.quic_targets.is_empty());
        assert!(request.service_targets.is_empty());
        assert!(request.circumvention_targets.is_empty());
        assert!(request.throughput_targets.is_empty());
        assert!(request.telegram_target.is_none());
        assert!(request.strategy_probe.is_none());
    }

    #[test]
    fn scan_path_mode_serializes_screaming_snake() {
        let json = serde_json::to_string(&ScanPathMode::RawPath).expect("serialize");
        assert_eq!(json, "\"RAW_PATH\"");
    }

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
    }

    #[test]
    fn scan_request_network_snapshot_defaults_to_none() {
        let json = r#"{
            "profileId": "test",
            "displayName": "Test",
            "pathMode": "RAW_PATH",
            "proxyHost": null,
            "proxyPort": null,
            "domainTargets": [],
            "dnsTargets": [],
            "tcpTargets": [],
            "whitelistSni": []
        }"#;
        let request: ScanRequest = serde_json::from_str(json).expect("deserialize");
        assert!(request.network_snapshot.is_none());
    }

    #[test]
    fn scan_report_deserializes_with_new_defaults() {
        let json = r#"{
            "sessionId": "session-1",
            "profileId": "default",
            "pathMode": "RAW_PATH",
            "startedAt": 1,
            "finishedAt": 2,
            "summary": "done",
            "results": []
        }"#;
        let report: ScanReport = serde_json::from_str(json).expect("deserialize");
        assert!(report.diagnoses.is_empty());
        assert!(report.classifier_version.is_none());
        assert!(report.pack_versions.is_empty());
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

    #[test]
    fn scan_request_with_network_snapshot_deserializes() {
        let json = r#"{
            "profileId": "p1",
            "displayName": "Test",
            "pathMode": "RAW_PATH",
            "proxyHost": null,
            "proxyPort": null,
            "domainTargets": [],
            "dnsTargets": [],
            "tcpTargets": [],
            "whitelistSni": [],
            "networkSnapshot": {
                "transport": "wifi",
                "validated": true,
                "captivePortal": false,
                "metered": false,
                "privateDnsMode": "system",
                "dnsServers": ["8.8.8.8"],
                "wifi": {
                    "frequencyBand": "5ghz",
                    "frequencyMhz": 5180,
                    "rssiDbm": -58,
                    "linkSpeedMbps": 866,
                    "rxLinkSpeedMbps": 780,
                    "txLinkSpeedMbps": 720,
                    "channelWidth": "80 MHz",
                    "wifiStandard": "802.11ax"
                },
                "cellular": {
                    "generation": "5g",
                    "roaming": false,
                    "operatorCode": "25001",
                    "dataNetworkType": "NR",
                    "serviceState": "in_service",
                    "carrierId": 42,
                    "signalLevel": 4,
                    "signalDbm": -95
                },
                "mtu": 1500,
                "capturedAtMs": 1700000000000
            }
        }"#;
        let request: ScanRequest = serde_json::from_str(json).expect("deserialize");
        let snap = request.network_snapshot.expect("network snapshot present");
        assert_eq!(snap.transport, "wifi");
        assert!(snap.validated);
        assert!(!snap.metered);
        assert_eq!(snap.dns_servers, vec!["8.8.8.8"]);
        assert_eq!(snap.wifi.as_ref().and_then(|wifi| wifi.frequency_mhz), Some(5180));
        assert_eq!(snap.wifi.as_ref().map(|wifi| wifi.channel_width.as_str()), Some("80 MHz"));
        assert_eq!(snap.cellular.as_ref().map(|cell| cell.data_network_type.as_str()), Some("NR"));
        assert_eq!(snap.cellular.as_ref().and_then(|cell| cell.signal_dbm), Some(-95));
        assert_eq!(snap.mtu, Some(1500));
    }

    #[test]
    fn scan_progress_new_probe_fields_default_to_none() {
        let json = r#"{
            "sessionId": "s1",
            "phase": "dns",
            "completedSteps": 1,
            "totalSteps": 8,
            "message": "DNS probe",
            "isFinished": false
        }"#;
        let progress: ScanProgress = serde_json::from_str(json).expect("deserialize");
        assert!(progress.latest_probe_target.is_none());
        assert!(progress.latest_probe_outcome.is_none());
    }

    #[test]
    fn scan_progress_serializes_probe_fields_when_present() {
        let progress = ScanProgress {
            session_id: "s1".to_string(),
            phase: "dns".to_string(),
            completed_steps: 1,
            total_steps: 8,
            message: "done".to_string(),
            is_finished: false,
            latest_probe_target: Some("youtube.com".to_string()),
            latest_probe_outcome: Some("ok".to_string()),
        };
        let json = serde_json::to_string(&progress).expect("serialize");
        assert!(json.contains("latestProbeTarget"));
        assert!(json.contains("youtube.com"));
        assert!(json.contains("latestProbeOutcome"));
    }
}
