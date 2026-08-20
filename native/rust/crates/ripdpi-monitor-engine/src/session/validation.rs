use crate::types::{EngineScanRequestWire, ScanRequest};

use super::request_validation::validate_scan_request;

pub(crate) struct ValidatedScanRequest(EngineScanRequestWire);

impl ValidatedScanRequest {
    pub(crate) fn as_wire(&self) -> &EngineScanRequestWire {
        &self.0
    }
}

impl TryFrom<EngineScanRequestWire> for ValidatedScanRequest {
    type Error = String;

    fn try_from(request: EngineScanRequestWire) -> Result<Self, Self::Error> {
        validate_scan_request(&request)?;
        Ok(Self(request))
    }
}

impl From<ValidatedScanRequest> for ScanRequest {
    fn from(request: ValidatedScanRequest) -> Self {
        request.0.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{
        DIAGNOSTICS_ENGINE_SCHEMA_VERSION, DiagnosticProfileFamily, ScanKind, ScanPathMode, StrategyProbeRequest,
        TcpTarget, ThroughputTarget,
    };

    fn request() -> EngineScanRequestWire {
        EngineScanRequestWire {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            profile_id: "test".to_string(),
            display_name: "Test".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::Connectivity,
            family: DiagnosticProfileFamily::General,
            region_tag: None,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            in_path_route: None,
            probe_tasks: Vec::new(),
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe: None,
            confirm_good_dpi_evidence: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
            native_log_level: None,
            log_context: None,
            diagnostic_tls_keylog_path: None,
        }
    }

    #[test]
    fn rejects_unbounded_throughput_work() {
        let mut request = request();
        request.throughput_targets.push(ThroughputTarget {
            id: "throughput".to_string(),
            label: "Throughput".to_string(),
            url: "https://example.com/payload".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            port: None,
            is_control: false,
            window_bytes: 8 * 1024 * 1024,
            runs: usize::MAX,
        });

        let error = validate_scan_request(&request).expect_err("unbounded throughput runs must fail");
        assert!(error.contains("throughput"), "unexpected validation error: {error}");
    }

    #[test]
    fn rejects_unbounded_fat_header_work() {
        let mut request = request();
        request.tcp_targets.push(TcpTarget {
            id: "tcp".to_string(),
            provider: "provider".to_string(),
            ip: "192.0.2.1".to_string(),
            port: 443,
            sni: Some("example.com".to_string()),
            asn: None,
            host_header: None,
            fat_header_requests: Some(usize::MAX),
            alt_port: None,
        });

        let error = validate_scan_request(&request).expect_err("unbounded fat-header requests must fail");
        assert!(error.contains("fatHeaderRequests"), "unexpected validation error: {error}");
    }

    #[test]
    fn rejects_unbounded_candidate_count_and_deadline() {
        let mut request = request();
        request.kind = ScanKind::StrategyProbe;
        request.strategy_probe = Some(StrategyProbeRequest {
            suite_id: "quick_v1".to_string(),
            base_proxy_config_json: Some(r#"{"mode":"ui","schemaVersion":2,"config":{}}"#.to_string()),
            target_selection: None,
            max_candidates: Some(usize::MAX),
        });

        let candidate_error = validate_scan_request(&request).expect_err("unbounded candidate count must fail");
        assert!(candidate_error.contains("maxCandidates"), "unexpected validation error: {candidate_error}");

        request.strategy_probe.as_mut().expect("strategy probe").max_candidates = Some(1);
        request.scan_deadline_ms = Some(u64::MAX);
        let deadline_error = validate_scan_request(&request).expect_err("unbounded scan deadline must fail");
        assert!(deadline_error.contains("scanDeadlineMs"), "unexpected validation error: {deadline_error}");
    }

    #[test]
    fn rejects_oversized_request_strings() {
        let mut request = request();
        request.display_name = "x".repeat(5 * 1024 * 1024);

        let error = validate_scan_request(&request).expect_err("oversized request strings must fail");
        assert!(error.contains("size"), "unexpected validation error: {error}");
    }
}
