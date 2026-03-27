use std::collections::BTreeMap;

use ripdpi_proxy_config::NetworkSnapshot;
use ripdpi_telemetry::recorder::RecorderSnapshot;
use serde::{Deserialize, Serialize};

use crate::util::*;

use super::observation::ProbeObservation;
use super::scan::*;
use super::strategy::StrategyProbeReport;
use super::target::*;

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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_progress: Option<StrategyProbeLiveProgress>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeProgressLane {
    Tcp,
    Quic,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeLiveProgress {
    pub lane: StrategyProbeProgressLane,
    pub candidate_index: usize,
    pub candidate_total: usize,
    pub candidate_id: String,
    pub candidate_label: String,
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
    pub observations: Vec<ProbeObservation>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub engine_analysis_version: Option<String>,
    #[serde(default)]
    pub diagnoses: Vec<Diagnosis>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classifier_version: Option<String>,
    #[serde(default)]
    pub pack_versions: BTreeMap<String, u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_report: Option<StrategyProbeReport>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metrics_summary: Option<RecorderSnapshot>,
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
        assert!(progress.strategy_probe_progress.is_none());
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
            strategy_probe_progress: Some(StrategyProbeLiveProgress {
                lane: StrategyProbeProgressLane::Tcp,
                candidate_index: 3,
                candidate_total: 14,
                candidate_id: "tcp_fake_tls".to_string(),
                candidate_label: "TCP fake TLS".to_string(),
            }),
        };
        let json = serde_json::to_string(&progress).expect("serialize");
        assert!(json.contains("latestProbeTarget"));
        assert!(json.contains("youtube.com"));
        assert!(json.contains("latestProbeOutcome"));
        assert!(json.contains("strategyProbeProgress"));
        assert!(json.contains("candidateLabel"));
    }

    #[test]
    fn scan_progress_deserializes_without_strategy_probe_progress() {
        let json = r#"{
            "sessionId": "s1",
            "phase": "tcp",
            "completedSteps": 1,
            "totalSteps": 8,
            "message": "Testing TCP",
            "isFinished": false
        }"#;
        let progress: ScanProgress = serde_json::from_str(json).expect("deserialize");
        assert!(progress.strategy_probe_progress.is_none());
    }
}
