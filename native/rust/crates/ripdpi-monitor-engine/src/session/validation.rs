use crate::types::ScanRequest;
use ripdpi_diagnostics_contracts::EngineScanRequestWire;

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
        DiagnosticProfileFamily, ScanKind, ScanPathMode, StrategyProbeRequest, TcpTarget, ThroughputTarget,
    };
    use ripdpi_diagnostics_contracts::DIAGNOSTICS_ENGINE_SCHEMA_VERSION;

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
            selective_matrix: None,
            ip_family_probe: None,
            http3_probe: None,
            pmtu_probe: None,
            tls_hop_comparison: None,
            quic_version_matrix: None,
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
    fn rejects_malformed_or_unbounded_telegram_targets_before_the_scan() {
        use crate::types::{TelegramDcEndpoint, TelegramTarget};

        let target = TelegramTarget {
            media_url: "https://[2001:db8::1]:8443?sample=1".into(),
            upload_ip: "upload.example.test".into(),
            upload_port: 443,
            dc_endpoints: vec![TelegramDcEndpoint { ip: "192.0.2.1".into(), label: "DC 1".into(), port: 443 }],
            stall_timeout_ms: 100,
            total_timeout_ms: 1000,
            upload_size_bytes: 1024,
        };
        let mut request = request();
        request.telegram_target = Some(target.clone());
        assert!(validate_scan_request(&request).is_ok());

        let repeated_dc = target.dc_endpoints[0].clone();
        for invalid in [
            TelegramTarget { media_url: "http://example.test/".into(), ..target.clone() },
            TelegramTarget { media_url: "https://example.test:65536/".into(), ..target.clone() },
            TelegramTarget { upload_ip: "upload.example.test:443".into(), ..target.clone() },
            TelegramTarget { upload_port: 0, ..target.clone() },
            TelegramTarget { upload_size_bytes: 0, ..target.clone() },
            TelegramTarget { upload_size_bytes: usize::MAX, ..target.clone() },
            TelegramTarget { total_timeout_ms: u64::MAX, ..target.clone() },
            TelegramTarget { stall_timeout_ms: target.total_timeout_ms + 1, ..target.clone() },
            TelegramTarget { dc_endpoints: vec![repeated_dc.clone(); 33], ..target.clone() },
            TelegramTarget { dc_endpoints: vec![repeated_dc; 2], ..target.clone() },
            TelegramTarget {
                dc_endpoints: vec![TelegramDcEndpoint { ip: "192.0.2.1".into(), label: "DC|2".into(), port: 443 }],
                ..target.clone()
            },
        ] {
            request.telegram_target = Some(invalid);
            assert!(validate_scan_request(&request).unwrap_err().contains("telegramTarget"));
        }
    }

    #[test]
    fn verified_download_is_one_bounded_https_target_in_its_own_profile() {
        use crate::types::{DownloadVerification, VERIFIED_DOWNLOAD_PROFILE_ID};
        let mut specimen = request();
        specimen.throughput_targets.push(ThroughputTarget {
            id: "download".into(),
            label: "Verified HTTPS download".into(),
            url: "https://example.test/control.bin".into(),
            connect_ip: None,
            connect_ips: Vec::new(),
            port: None,
            is_control: false,
            window_bytes: 3,
            runs: 1,
            upload_body_bytes: None,
            verification: Some(DownloadVerification {
                expected_body_bytes: 2,
                sha256: "2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df".into(),
                ech_comparison_ip: None,
            }),
        });
        assert!(validate_scan_request(&specimen).is_err());
        specimen.profile_id = VERIFIED_DOWNLOAD_PROFILE_ID.into();
        assert!(validate_scan_request(&specimen).is_ok());
        let good = specimen.throughput_targets[0].clone();
        for url in ["http://example.test/control.bin", "https://user@example.test/", "https://example.test/?key=bad"] {
            specimen.throughput_targets[0].url = url.into();
            assert!(validate_scan_request(&specimen).is_err(), "{url}");
        }
        specimen.throughput_targets[0] = good.clone();
        specimen.throughput_targets[0].runs = 2;
        assert!(validate_scan_request(&specimen).is_err());
        specimen.throughput_targets[0] = good.clone();
        specimen.throughput_targets[0].window_bytes = 1024 * 1024 + 2;
        specimen.throughput_targets[0].verification.as_mut().unwrap().expected_body_bytes = 1024 * 1024 + 1;
        assert!(validate_scan_request(&specimen).is_err());
        specimen.throughput_targets[0] = good.clone();
        specimen.throughput_targets.push(good);
        assert!(validate_scan_request(&specimen).is_err());
    }

    #[test]
    fn ech_comparison_requires_its_own_profile_fixed_ip_and_short_body() {
        use crate::types::{
            ECH_TRANSFER_COMPARISON_PROFILE_ID, MAX_ECH_COMPARISON_BODY_BYTES, VERIFIED_DOWNLOAD_PROFILE_ID,
        };
        let mut specimen = request();
        let good: ThroughputTarget = serde_json::from_value(serde_json::json!({
            "id": "ech-control",
            "label": "ECH comparison",
            "url": "https://control.example:8443/control.bin",
            "windowBytes": MAX_ECH_COMPARISON_BODY_BYTES + 1,
            "runs": 1,
            "verification": {
                "expectedBodyBytes": MAX_ECH_COMPARISON_BODY_BYTES,
                "sha256": "2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df",
                "echComparisonIp": "192.0.2.10",
            },
        }))
        .unwrap();
        specimen.throughput_targets.push(good.clone());
        specimen.profile_id = VERIFIED_DOWNLOAD_PROFILE_ID.into();
        assert!(validate_scan_request(&specimen).is_err());
        specimen.profile_id = ECH_TRANSFER_COMPARISON_PROFILE_ID.into();
        assert!(validate_scan_request(&specimen).is_ok());

        for url in
            ["https://192.0.2.10/control.bin", "http://control.example/control.bin", "https://control.example:0/"]
        {
            specimen.throughput_targets[0].url = url.into();
            assert!(validate_scan_request(&specimen).is_err(), "{url}");
        }
        specimen.throughput_targets[0] = good.clone();
        specimen.throughput_targets[0].verification.as_mut().unwrap().ech_comparison_ip = None;
        assert!(validate_scan_request(&specimen).is_err());
        specimen.throughput_targets[0] = good.clone();
        specimen.throughput_targets[0].verification.as_mut().unwrap().ech_comparison_ip = Some("::".parse().unwrap());
        assert!(validate_scan_request(&specimen).is_err());
        specimen.throughput_targets[0] = good;
        specimen.throughput_targets[0].verification.as_mut().unwrap().expected_body_bytes += 1;
        specimen.throughput_targets[0].window_bytes += 1;
        assert!(validate_scan_request(&specimen).is_err());
    }

    #[test]
    fn verified_upload_requires_its_profile_and_a_single_bounded_target() {
        use crate::types::{MAX_VERIFIED_UPLOAD_BYTES, VERIFIED_UPLOAD_PROFILE_ID};
        let mut specimen = request();
        let target = ThroughputTarget {
            id: VERIFIED_UPLOAD_PROFILE_ID.into(),
            label: "Verified HTTPS upload".into(),
            url: "https://example.test/upload".into(),
            connect_ip: None,
            connect_ips: Vec::new(),
            port: None,
            is_control: false,
            window_bytes: MAX_VERIFIED_UPLOAD_BYTES,
            runs: 1,
            verification: None,
            upload_body_bytes: Some(MAX_VERIFIED_UPLOAD_BYTES),
        };
        specimen.throughput_targets.push(target.clone());
        assert!(validate_scan_request(&specimen).is_err());
        specimen.profile_id = VERIFIED_UPLOAD_PROFILE_ID.into();
        assert!(validate_scan_request(&specimen).is_ok());

        for invalid in [
            ThroughputTarget { url: "http://example.test/upload".into(), ..target.clone() },
            ThroughputTarget { url: "https://example.test/upload?key=bad".into(), ..target.clone() },
            ThroughputTarget { connect_ip: Some("192.0.2.1".into()), ..target.clone() },
            ThroughputTarget { runs: 2, ..target.clone() },
            ThroughputTarget { window_bytes: 1, ..target.clone() },
            ThroughputTarget { upload_body_bytes: None, ..target.clone() },
            ThroughputTarget {
                window_bytes: MAX_VERIFIED_UPLOAD_BYTES + 1,
                upload_body_bytes: Some(MAX_VERIFIED_UPLOAD_BYTES + 1),
                ..target.clone()
            },
        ] {
            specimen.throughput_targets[0] = invalid;
            assert!(validate_scan_request(&specimen).is_err());
        }
        specimen.throughput_targets = vec![target.clone(), target];
        assert!(validate_scan_request(&specimen).is_err());
    }

    #[test]
    fn rejects_unbounded_throughput_work() {
        let mut request = request();
        request.throughput_targets.push(ThroughputTarget {
            upload_body_bytes: None,
            verification: None,
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

    fn tcp_target(fat_header_requests: Option<usize>) -> TcpTarget {
        TcpTarget {
            id: "tcp".to_string(),
            provider: "provider".to_string(),
            ip: "192.0.2.1".to_string(),
            port: 443,
            sni: Some("example.com".to_string()),
            asn: None,
            host_header: None,
            fat_header_requests,
            alt_port: None,
        }
    }

    #[test]
    fn rejects_unbounded_fat_header_work() {
        let mut request = request();
        request.tcp_targets.push(tcp_target(Some(usize::MAX)));
        let error = validate_scan_request(&request).expect_err("unbounded fat-header requests must fail");
        assert!(error.contains("fatHeaderRequests"), "unexpected validation error: {error}");
    }

    #[test]
    fn counts_default_fat_header_series_in_the_work_budget() {
        let mut request = request();
        request.tcp_targets = vec![tcp_target(None); 256];
        assert!(validate_scan_request(&request).is_ok(), "256 default 16-request series fit the budget");

        request.tcp_targets[0].fat_header_requests = Some(64);
        let error = validate_scan_request(&request).expect_err("the longer series exceeds the aggregate budget");
        assert!(error.contains("total fatHeaderRequests"), "{error}");
    }

    #[test]
    fn reserves_fat_header_work_for_alternate_sni_and_port() {
        let mut request = request();
        request.whitelist_sni = (0..5).map(|index| format!("candidate{index}.test")).collect();
        let target = TcpTarget { alt_port: Some(8443), ..tcp_target(Some(64)) };
        // Ten targets with one baseline, four SNI alternates and one alternate port use 3840 requests.
        request.tcp_targets = vec![target.clone(); 10];
        assert!(validate_scan_request(&request).is_ok(), "the fifth SNI candidate is outside the runtime cap");
        request.tcp_targets.push(target);
        let error = validate_scan_request(&request).expect_err("11 such targets require up to 4224 requests");
        assert!(error.contains("total fatHeaderRequests"), "{error}");
    }

    #[test]
    fn rejects_invalid_tcp_target_and_alternate_configuration() {
        let mut request = request();
        for (target, field) in [
            (TcpTarget { ip: "example.test".into(), ..tcp_target(None) }, "numeric IP"),
            (TcpTarget { port: 0, ..tcp_target(None) }, "nonzero ports"),
            (TcpTarget { alt_port: Some(0), ..tcp_target(None) }, "nonzero ports"),
            (TcpTarget { sni: Some(String::new()), ..tcp_target(None) }, "tcpTargets.sni"),
            (TcpTarget { host_header: Some("bad:abc".into()), ..tcp_target(None) }, "tcpTargets.hostHeader"),
            (TcpTarget { host_header: Some("x".repeat(260)), ..tcp_target(None) }, "tcpTargets.hostHeader"),
        ] {
            request.tcp_targets = vec![target];
            let error = validate_scan_request(&request).expect_err("invalid TCP target");
            assert!(error.contains(field), "{error}");
        }

        request.tcp_targets = vec![TcpTarget { sni: Some("192.0.2.1".into()), ..tcp_target(None) }];
        assert!(validate_scan_request(&request).is_ok(), "TLS accepts an IP identity without wire SNI");
        request.whitelist_sni = vec!["192.0.2.1".into()];
        let error = validate_scan_request(&request).expect_err("alternate SNI must be a DNS name");
        assert!(error.contains("whitelistSni requires DNS names"), "{error}");
        request.whitelist_sni = vec!["candidate.test".into(); 257];
        let error = validate_scan_request(&request).expect_err("the input list has a separate size cap");
        assert!(error.contains("whitelistSni count"), "{error}");
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
    fn configured_only_probes_require_matching_task_and_config() {
        use crate::types::{
            Http3ProbeConfig, IpFamilyProbeConfig, PmtuProbeConfig, ProbeTask, ProbeTaskFamily,
            QuicVersionMatrixConfig, TlsHopComparisonConfig,
        };

        for (family, field) in [
            (ProbeTaskFamily::Http3, "http3Probe"),
            (ProbeTaskFamily::Pmtu, "pmtuProbe"),
            (ProbeTaskFamily::TlsHopComparison, "tlsHopComparison"),
            (ProbeTaskFamily::QuicVersionMatrix, "quicVersionMatrix"),
            (ProbeTaskFamily::IpFamily, "ipFamilyProbe"),
        ] {
            let mut wire = request();
            wire.probe_tasks.push(ProbeTask {
                family: family.clone(),
                target_id: "target".into(),
                label: "Test".into(),
            });
            assert!(validate_scan_request(&wire).unwrap_err().contains(field));
            match family {
                ProbeTaskFamily::Http3 => wire.http3_probe = Some(Http3ProbeConfig::default()),
                ProbeTaskFamily::Pmtu => wire.pmtu_probe = Some(PmtuProbeConfig::default()),
                ProbeTaskFamily::TlsHopComparison => {
                    wire.tls_hop_comparison = Some(TlsHopComparisonConfig {
                        host: "192.0.2.1".into(),
                        control_sni: "control.example".into(),
                        test_sni: "test.example".into(),
                    });
                }
                ProbeTaskFamily::QuicVersionMatrix => {
                    wire.quic_version_matrix = Some(QuicVersionMatrixConfig {
                        host: "quic.example".into(),
                        connect_ip: None,
                        control_sni: "control.example".into(),
                        test_sni: "test.example".into(),
                        residual_version: None,
                    });
                }
                ProbeTaskFamily::IpFamily => {
                    wire.ip_family_probe = Some(IpFamilyProbeConfig {
                        version: 1,
                        ipv4_address: "192.0.2.1".into(),
                        ipv6_address: "2001:db8::1".into(),
                        port: 443,
                        timeout_ms: 1500,
                    });
                }
                _ => unreachable!("only configured probe families"),
            }
            wire.probe_tasks.clear();
            assert!(validate_scan_request(&wire).unwrap_err().contains(field));
        }
    }

    #[test]
    fn bounds_route_experiment_work() {
        let mut request = request();
        request.route_probe = Some(crate::types::RouteProbeConfig {
            stable_flow_attempts: usize::MAX,
            diversity_buckets: 3,
            diversity_on_failure_only: true,
        });
        assert!(validate_scan_request(&request).unwrap_err().contains("stableFlowAttempts"));
        request.route_probe.as_mut().unwrap().stable_flow_attempts = 2;
        request.route_probe.as_mut().unwrap().diversity_buckets = usize::MAX;
        assert!(validate_scan_request(&request).unwrap_err().contains("diversityBuckets"));
        request.route_probe.as_mut().unwrap().diversity_buckets = 3;
        assert!(validate_scan_request(&request).is_ok());
    }

    #[test]
    fn rejects_oversized_request_strings() {
        let mut request = request();
        request.display_name = "x".repeat(5 * 1024 * 1024);

        let error = validate_scan_request(&request).expect_err("oversized request strings must fail");
        assert!(error.contains("size"), "unexpected validation error: {error}");
    }
}
