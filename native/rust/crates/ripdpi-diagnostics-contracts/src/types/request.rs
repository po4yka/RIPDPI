mod config;
mod progress;
mod report;
mod result;

pub use config::{InPathProxyCredentials, InPathRoute, RouteProbeConfig, ScanRequest};
pub use progress::{ScanProgress, StrategyProbeLiveProgress, StrategyProbeProgressLane};
pub use report::{
    CandidateRuntimeCleanupDetail, CandidateRuntimeCleanupOutcome, CandidateRuntimeCleanupReceipt, ScanReport,
    ScanReportDisposition,
};
pub use result::{ProbeDetail, ProbeResult};

#[cfg(test)]
mod tests {
    use super::{
        ScanProgress, ScanReport, ScanReportDisposition, ScanRequest, StrategyProbeLiveProgress,
        StrategyProbeProgressLane,
    };
    use crate::types::{DiagnosticProfileFamily, ScanKind};

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
        assert!(request.route_probe.is_none());
        assert!(request.in_path_route.is_none());
        assert!(request.diagnostic_tls_keylog_path.is_none());
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
    fn scan_request_preserves_diagnostic_tls_keylog_path_through_wire_conversion() {
        let json = r#"{
            "schemaVersion": 3,
            "profileId": "test",
            "displayName": "Test",
            "pathMode": "RAW_PATH",
            "proxyHost": null,
            "proxyPort": null,
            "domainTargets": [],
            "dnsTargets": [],
            "tcpTargets": [],
            "whitelistSni": [],
            "diagnosticTlsKeylogPath": "/app/files/diagnostics/tls.keys"
        }"#;
        let wire: crate::wire::EngineScanRequestWire = serde_json::from_str(json).expect("deserialize wire");
        let request: ScanRequest = wire.into();

        assert_eq!(request.diagnostic_tls_keylog_path.as_deref(), Some("/app/files/diagnostics/tls.keys"),);

        let encoded: crate::wire::EngineScanRequestWire = request.into();
        assert_eq!(encoded.diagnostic_tls_keylog_path.as_deref(), Some("/app/files/diagnostics/tls.keys"),);
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
        assert!(report.execution_plan.is_none());
        assert_eq!(report.report_disposition, ScanReportDisposition::Terminal);
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
                succeeded_targets: 0,
                total_targets: 0,
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
