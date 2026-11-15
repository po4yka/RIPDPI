use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use ripdpi_diagnostics_tls::tls::TlsObservation;
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{
    describe_http_observation, is_blockpage, try_http_request_targets_with_cancel,
};
use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, append_tls_comparison_details,
    classify_tls_signal, preferred_tls_observation, tls_key_log_callback_for_path,
    try_tls_handshake_targets_with_options_and_abort,
};
use crate::connectivity::adapters::transport::{TargetAddress, TransportConfig, domain_connect_targets};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

use super::super::trigger_fuzzing::{append_http_trigger_fuzzing_details, append_tls_trigger_fuzzing_details};
use super::support::append_route_details;

pub fn run_domain_probe(
    target: &DomainTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    run_domain_probe_with_key_log(target, transport, tls_verifier, None, None)
}

pub fn run_domain_probe_with_key_log(
    target: &DomainTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    keylog_path: Option<&str>,
    cancel: Option<&AtomicBool>,
) -> ProbeResult {
    let key_log = keylog_path.map(tls_key_log_callback_for_path);
    let should_abort = || abort_reason(cancel);
    run_domain_probe_with_tls_probe(target, transport, cancel, |connect_targets, port, profile| {
        let options = ProbeStreamOptions {
            profile,
            application_protocol: ApplicationProtocolPolicy::TemplateDefault,
            tls_verifier,
            key_log: key_log.as_ref(),
        };
        try_tls_handshake_targets_with_options_and_abort(
            connect_targets,
            port,
            transport,
            &target.host,
            &options,
            &should_abort,
        )
    })
    .unwrap_or_else(|reason| ProbeResult {
        probe_type: "domain_reachability".to_string(),
        target: target.host.clone(),
        outcome: reason.to_string(),
        details: Vec::new(),
    })
}

fn abort_reason(cancel: Option<&AtomicBool>) -> Option<&'static str> {
    // Acquire observes cancellation published by the scan owner.
    if cancel.is_some_and(|flag| flag.load(Ordering::Acquire)) {
        Some("scan_cancelled")
    } else if active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline) {
        Some("scan_deadline_exceeded")
    } else {
        None
    }
}

fn run_domain_probe_with_tls_probe(
    target: &DomainTarget,
    transport: &TransportConfig,
    cancel: Option<&AtomicBool>,
    mut probe_tls: impl FnMut(&[TargetAddress], u16, TlsClientProfile) -> TlsObservation,
) -> Result<ProbeResult, &'static str> {
    let check_abort = || abort_reason(cancel).map_or(Ok(()), Err);
    check_abort()?;
    let https_port = target.https_port.unwrap_or(443);
    let http_port = target.http_port.unwrap_or(80);
    let connect_targets = domain_connect_targets(target);
    let mut probe_profile = |profile| -> Result<_, &'static str> {
        check_abort()?;
        let observation = probe_tls(&connect_targets, https_port, profile);
        check_abort()?;
        Ok(observation)
    };
    let mut tls13 = probe_profile(TlsClientProfile::Tls13Only)?;
    let tls12 = probe_profile(TlsClientProfile::Tls12Only)?;
    let tls_ech = probe_profile(TlsClientProfile::Tls13WithEch)?;
    let http = try_http_request_targets_with_cancel(
        &connect_targets,
        http_port,
        transport,
        &target.host,
        &target.http_path,
        cancel,
    );
    check_abort()?;
    let alt_svc_value = http.response.as_ref().and_then(|r| r.headers.get("alt-svc")).cloned();
    let h3_advertised = alt_svc_value.as_ref().is_some_and(|v| v.contains("h3"));

    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else if tls_ech.status == "tls_ok" {
        "tls_ech_only".to_string()
    } else if is_blockpage(&http) {
        "http_blockpage".to_string()
    } else if http.status == "http_ok" {
        "http_ok".to_string()
    } else {
        "unreachable".to_string()
    };

    // Single retry on total failure to distinguish transient from consistent blocking
    let (outcome, probe_retry_count) = if outcome == "unreachable" {
        let retry = probe_profile(TlsClientProfile::Tls13Only)?;
        if retry.status == "tls_ok" {
            tls13 = retry;
            ("tls_ok".to_string(), 1usize)
        } else {
            ("unreachable".to_string(), 1usize)
        }
    } else {
        (outcome, 0usize)
    };
    let tls_signal = classify_tls_signal(&tls13, &tls12);
    let preferred_tls = preferred_tls_observation(&tls13, &tls12);
    let route_local_addr =
        if outcome == "tls_ech_only" { tls_ech.local_addr } else { preferred_tls.local_addr.or(tls_ech.local_addr) };
    let route_report = if outcome == "tls_ech_only" {
        tls_ech.route_report.as_ref()
    } else {
        preferred_tls.route_report.as_ref().or(tls_ech.route_report.as_ref())
    };
    let connected_addr = if outcome == "tls_ech_only" {
        tls_ech.connected_addr
    } else {
        preferred_tls.connected_addr.or(tls_ech.connected_addr)
    };

    let mut result = ProbeResult {
        probe_type: "domain_reachability".to_string(),
        target: target.host.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "tlsStatus".to_string(), value: preferred_tls.status.clone() },
            ProbeDetail {
                key: "tlsVersion".to_string(),
                value: preferred_tls.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tlsError".to_string(),
                value: preferred_tls.error.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail {
                key: "tlsFailureStage".to_string(),
                value: preferred_tls.failure_stage.map_or("none", |stage| stage.as_str()).to_string(),
            },
            ProbeDetail { key: "tlsSignal".to_string(), value: tls_signal.to_string() },
            ProbeDetail { key: "tls13Status".to_string(), value: tls13.status.clone() },
            ProbeDetail {
                key: "tls13Version".to_string(),
                value: tls13.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tls13Error".to_string(),
                value: tls13.error.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail { key: "tls12Status".to_string(), value: tls12.status.clone() },
            ProbeDetail {
                key: "tls12Version".to_string(),
                value: tls12.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tls12Error".to_string(),
                value: tls12.error.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail { key: "tlsEchStatus".to_string(), value: tls_ech.status.clone() },
            ProbeDetail {
                key: "tlsEchVersion".to_string(),
                value: tls_ech.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tlsEchError".to_string(),
                value: tls_ech.error.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail {
                key: "tlsEchResolutionDetail".to_string(),
                value: tls_ech.ech_resolution_detail.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail { key: "httpStatus".to_string(), value: http.status.clone() },
            ProbeDetail { key: "httpStatusCode".to_string(), value: http_status_code(&http.status) },
            ProbeDetail { key: "httpStatusClass".to_string(), value: http_status_class(&http.status) },
            ProbeDetail { key: "httpResponse".to_string(), value: describe_http_observation(&http) },
            ProbeDetail { key: "h3Advertised".to_string(), value: h3_advertised.to_string() },
            ProbeDetail { key: "altSvc".to_string(), value: alt_svc_value.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "isControl".to_string(), value: target.is_control.to_string() },
            ProbeDetail { key: "probeRetryCount".to_string(), value: probe_retry_count.to_string() },
        ],
    };
    append_tls_comparison_details(&mut result.details, &result.outcome, &tls13, &tls12, &tls_ech);
    if let Some(addr) = connected_addr {
        result.details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    if matches!(transport, TransportConfig::Direct { .. }) {
        // Literal addresses are already resolved. Use the configured pin when no TLS attempt
        // connected. For a hostname report only the peer the actual connection selected.
        let resolved = connected_addr.or_else(|| match connect_targets.first()? {
            TargetAddress::Ip(ip) => Some(std::net::SocketAddr::new(*ip, https_port)),
            TargetAddress::Host(host) => host.parse().ok().map(|ip| std::net::SocketAddr::new(ip, https_port)),
        });
        if let Some(address) = resolved {
            result.details.insert(0, ProbeDetail { key: "resolved".into(), value: address.to_string() });
        }
    }
    append_route_details(&mut result.details, "", route_local_addr, route_report);
    check_abort()?;
    if http.status != "http_ok" {
        append_http_trigger_fuzzing_details(&mut result.details, target, transport, http.status.as_str());
    }
    check_abort()?;
    if preferred_tls.status != "tls_ok" {
        append_tls_trigger_fuzzing_details(&mut result.details, target, transport, preferred_tls.status.as_str());
    }
    check_abort()?;
    Ok(result)
}

fn http_status_code(status: &str) -> String {
    status.strip_prefix("http_status_").unwrap_or("").to_string()
}

fn http_status_class(status: &str) -> String {
    match status.strip_prefix("http_status_").and_then(|value| value.parse::<u16>().ok()) {
        Some(200..=299) => "success".to_string(),
        Some(300..=399) => "redirect".to_string(),
        Some(400..=499) => "client_error".to_string(),
        Some(500..=599) => "server_error".to_string(),
        Some(_) => "nonstandard".to_string(),
        None if status == "http_ok" => "success".to_string(),
        None if status == "http_blockpage" => "blockpage".to_string(),
        None if status == "http_unreachable" => "unreachable".to_string(),
        None if status == "not_run" => "not_run".to_string(),
        None => "unknown".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::{http_status_class, http_status_code};
    use crate::types::{DomainTarget, TransportFailureKind};
    use ripdpi_diagnostics_classification::observations::observation_for_probe;
    use ripdpi_diagnostics_tls::tls::{ProbeStreamFailureStage, TlsClientProfile, TlsObservation};

    fn failed_probe() -> TlsObservation {
        TlsObservation {
            status: "tls_handshake_failed".into(),
            version: None,
            error: Some("connection timed out".into()),
            failure_stage: Some(ProbeStreamFailureStage::TcpConnect),
            failure_duration_ms: Some(100),
            certificate_anomaly: false,
            ech_resolution_detail: None,
            ech_bootstrap_policy: None,
            ech_bootstrap_resolver_id: None,
            ech_outer_extension_policy: None,
            ech_first_flight_plan: None,
            transport_open_ms: None,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            local_socket_ttl: None,
            ja3_fingerprint: None,
            client_hello_capture: ripdpi_diagnostics_tls::tls::ClientHelloCapture::NotSent,
            tls_alert_code: None,
            tls_alert_description: None,
            tls_server_hello_received: None,
            connected_addr: None,
            local_addr: None,
            cdn_provider: None,
            route_report: None,
        }
    }

    #[test]
    fn successful_retry_replaces_tls_failure_evidence() {
        let failure = failed_probe();
        let success = TlsObservation {
            status: "tls_ok".into(),
            version: Some("TLS1.3".into()),
            error: None,
            failure_stage: None,
            failure_duration_ms: None,
            connected_addr: Some("127.0.0.2:443".parse().unwrap()),
            ..failure.clone()
        };
        let http_server = crate::test_fixtures::HttpTextServer::start_text("HTTP/1.1 503 Service Unavailable", "busy");
        let target = DomainTarget {
            host: "localhost".into(),
            connect_ip: Some("127.0.0.1".into()),
            connect_ips: vec![],
            https_port: Some(9),
            http_port: Some(http_server.port()),
            http_path: "/".into(),
            is_control: false,
            concurrency_probe: None,
        };
        let mut attempts = 0;
        let result = super::run_domain_probe_with_tls_probe(
            &target,
            &super::TransportConfig::Direct { route_experiment: None },
            None,
            |_, _, _| {
                attempts += 1;
                if attempts == 4 { success.clone() } else { failure.clone() }
            },
        )
        .unwrap();
        assert_eq!(attempts, 4);
        assert_eq!(result.outcome, "tls_ok");
        let detail = |key: &str| result.details.iter().find(|detail| detail.key == key).unwrap().value.as_str();
        assert_eq!(detail("tlsStatus"), "tls_ok");
        assert_eq!(detail("tlsVersion"), "TLS1.3");
        assert_eq!(detail("tlsError"), "none");
        assert_eq!(detail("tlsFailureStage"), "none");
        assert_eq!(detail("tls13Status"), "tls_ok");
        assert_eq!(detail("tls13Error"), "none");
        assert_eq!(detail("tls12Error"), "connection timed out");
        assert_eq!(detail("connectedIp"), "127.0.0.2");
        let domain = observation_for_probe(&result).unwrap().domain.unwrap();
        assert_eq!(domain.transport_failure, TransportFailureKind::None);
        assert_eq!(domain.tls_error, None);
    }

    #[test]
    fn protocol_version_alert_does_not_convert_a_split_to_tls_ok() {
        let connected_addr = Some("127.0.0.1:443".parse().unwrap());
        let tls13 = TlsObservation {
            status: "tls_ok".into(),
            version: Some("TLS1.3".into()),
            error: None,
            failure_stage: None,
            failure_duration_ms: None,
            connected_addr,
            ..failed_probe()
        };
        let tls12 = TlsObservation {
            error: Some("received fatal alert: ProtocolVersion".into()),
            failure_stage: Some(ProbeStreamFailureStage::TlsHandshake),
            tls_alert_code: Some(70),
            tls_server_hello_received: Some(false),
            tls_handshake_ms: Some(2),
            connected_addr,
            ..failed_probe()
        };
        let http_server = crate::test_fixtures::HttpTextServer::start_text("HTTP/1.1 200 OK", "ok");
        let target = DomainTarget {
            host: "localhost".into(),
            connect_ip: Some("127.0.0.1".into()),
            connect_ips: vec![],
            https_port: Some(9),
            http_port: Some(http_server.port()),
            http_path: "/".into(),
            is_control: false,
            concurrency_probe: None,
        };
        let result = super::run_domain_probe_with_tls_probe(
            &target,
            &super::TransportConfig::Direct { route_experiment: None },
            None,
            |_, _, profile| match profile {
                TlsClientProfile::Tls13Only => tls13.clone(),
                TlsClientProfile::Tls12Only => tls12.clone(),
                _ => failed_probe(),
            },
        )
        .unwrap();

        assert_eq!(result.outcome, "tls_version_split");
        assert!(
            result
                .details
                .iter()
                .any(|detail| { detail.key == "tlsVersionComparisonScope" && detail.value == "same_peer" })
        );
        assert!(result.details.iter().any(|detail| detail.key == "tlsAlertCode" && detail.value == "70"));
        assert!(result.details.iter().any(|detail| detail.key == "tlsAlertSource" && detail.value == "unknown"));
    }

    #[test]
    fn proxied_domain_does_not_report_a_local_resolver_result() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        use std::time::Duration;

        use crate::types::DomainTarget;
        use ripdpi_diagnostics_tls::tls::TlsObservation;

        let proxy = TcpListener::bind("127.0.0.1:0").unwrap();
        let transport = super::TransportConfig::Socks5 {
            host: "127.0.0.1".into(),
            port: proxy.local_addr().unwrap().port(),
            credentials: None,
        };
        let target = DomainTarget {
            host: "localhost".into(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: Some(443),
            http_port: Some(80),
            http_path: "/".into(),
            is_control: false,
            concurrency_probe: None,
        };
        proxy.set_nonblocking(true).unwrap();
        std::thread::scope(|scope| {
            scope.spawn(move || {
                let deadline = std::time::Instant::now() + Duration::from_secs(2);
                let mut stream = loop {
                    match proxy.accept() {
                        Ok((stream, _)) => break stream,
                        Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(std::time::Instant::now() < deadline, "no SOCKS connection");
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(err) => panic!("SOCKS accept: {err}"),
                    }
                };
                stream.set_nonblocking(false).unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                let mut greeting = [0; 3];
                stream.read_exact(&mut greeting).unwrap();
                assert_eq!(greeting, [5, 1, 0]);
                stream.write_all(&[5, 0]).unwrap();
                let mut request = [0; 16];
                stream.read_exact(&mut request).unwrap();
                assert_eq!(&request, b"\x05\x01\x00\x03\x09localhost\x00\x50");
                stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 80]).unwrap();
                let mut request = Vec::new();
                let mut byte = [0];
                while !request.ends_with(b"\r\n\r\n") {
                    assert!(request.len() < 1024);
                    stream.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                assert!(request.starts_with(b"GET / HTTP/1.1\r\nHost: localhost\r\n"));
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").unwrap();
            });
            let result = super::run_domain_probe_with_tls_probe(&target, &transport, None, |_, _, _| TlsObservation {
                status: "tls_ok".into(),
                error: None,
                failure_stage: None,
                ..failed_probe()
            })
            .unwrap();
            assert!(result.details.iter().any(|detail| detail.key == "httpStatus" && detail.value == "http_ok"));
            assert!(!result.details.iter().any(|detail| detail.key == "resolved"));
        });
    }

    #[test]
    fn domain_probe_uses_later_pinned_address_and_preserves_host() {
        use crate::test_fixtures::HttpTextServer;
        use crate::types::DomainTarget;
        let server = HttpTextServer::start(|request| {
            assert!(String::from_utf8_lossy(&request).contains("\r\nHost: localhost:"));
            b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".to_vec()
        });
        let target = DomainTarget {
            host: "localhost".into(),
            connect_ip: Some("::1".into()),
            connect_ips: vec!["127.0.0.1".into()],
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".into(),
            is_control: false,
            concurrency_probe: None,
        };
        let result = super::run_domain_probe(&target, &super::TransportConfig::Direct { route_experiment: None }, None);
        assert_eq!(result.outcome, "http_ok");
    }

    #[test]
    fn http_status_details_extract_code_and_class() {
        assert_eq!(http_status_code("http_status_301"), "301");
        assert_eq!(http_status_class("http_status_200"), "success");
        assert_eq!(http_status_class("http_status_301"), "redirect");
        assert_eq!(http_status_class("http_status_400"), "client_error");
        assert_eq!(http_status_class("http_status_503"), "server_error");
    }

    #[test]
    fn http_status_details_classify_symbolic_statuses() {
        assert_eq!(http_status_code("http_ok"), "");
        assert_eq!(http_status_class("http_ok"), "success");
        assert_eq!(http_status_class("http_blockpage"), "blockpage");
        assert_eq!(http_status_class("http_unreachable"), "unreachable");
        assert_eq!(http_status_class("not_run"), "not_run");
        assert_eq!(http_status_class("weird"), "unknown");
    }
}
