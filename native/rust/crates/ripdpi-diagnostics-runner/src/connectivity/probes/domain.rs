use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{describe_http_observation, is_blockpage, try_http_request_targets};
use crate::connectivity::adapters::tls::{
    TlsClientProfile, classify_tls_signal, is_server_tls_version_rejection, preferred_tls_observation,
    tls_key_log_callback_for_path, try_tls_handshake_targets_with_key_log,
};
use crate::connectivity::adapters::transport::{TransportConfig, domain_connect_targets, resolve_addresses};
use crate::connectivity::adapters::util::format_socket_result;
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

use super::super::trigger_fuzzing::{append_http_trigger_fuzzing_details, append_tls_trigger_fuzzing_details};
use super::support::append_route_details;

pub fn run_domain_probe(
    target: &DomainTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    run_domain_probe_with_key_log(target, transport, tls_verifier, None)
}

pub fn run_domain_probe_with_key_log(
    target: &DomainTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    keylog_path: Option<&str>,
) -> ProbeResult {
    let key_log = keylog_path.map(tls_key_log_callback_for_path);
    let https_port = target.https_port.unwrap_or(443);
    let http_port = target.http_port.unwrap_or(80);
    let connect_targets = domain_connect_targets(target);
    // Keep this informational lookup on the first candidate, as before multi-peer fallback.
    // Resolving every fallback name here would consume the deadline before pinned peers run.
    let resolved = match connect_targets.first() {
        Some(target) => resolve_addresses(target, https_port).map_err(|error| error.to_string()),
        None => Err(ripdpi_diagnostics_transport::transport::TransportError::NoTargetCandidates.to_string()),
    };
    let tls13 = try_tls_handshake_targets_with_key_log(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
        tls_verifier,
        key_log.as_ref(),
    );
    let tls12 = try_tls_handshake_targets_with_key_log(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
        tls_verifier,
        key_log.as_ref(),
    );
    let tls_ech = try_tls_handshake_targets_with_key_log(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
        key_log.as_ref(),
    );
    let http = try_http_request_targets(&connect_targets, http_port, transport, &target.host, &target.http_path, false);
    let alt_svc_value = http.response.as_ref().and_then(|r| r.headers.get("alt-svc")).cloned();
    let h3_advertised = alt_svc_value.as_ref().is_some_and(|v| v.contains("h3"));
    let tls_signal = classify_tls_signal(&tls13, &tls12);
    let preferred_tls = preferred_tls_observation(&tls13, &tls12);

    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        if is_server_tls_version_rejection(&tls13, &tls12) {
            "tls_ok".to_string()
        } else {
            "tls_version_split".to_string()
        }
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
        let retry = try_tls_handshake_targets_with_key_log(
            &connect_targets,
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
            key_log.as_ref(),
        );
        if retry.status == "tls_ok" { ("tls_ok".to_string(), 1usize) } else { ("unreachable".to_string(), 1usize) }
    } else {
        (outcome, 0usize)
    };
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
            ProbeDetail { key: "resolved".to_string(), value: format_socket_result(&resolved) },
            ProbeDetail { key: "tlsStatus".to_string(), value: preferred_tls.status.clone() },
            ProbeDetail {
                key: "tlsVersion".to_string(),
                value: preferred_tls.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tlsError".to_string(),
                value: preferred_tls.error.clone().unwrap_or_else(|| "none".to_string()),
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
    if let Some(addr) = connected_addr {
        result.details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    append_route_details(&mut result.details, "", route_local_addr, route_report);
    if http.status != "http_ok" {
        append_http_trigger_fuzzing_details(&mut result.details, target, transport, http.status.as_str());
    }
    if preferred_tls.status != "tls_ok" {
        append_tls_trigger_fuzzing_details(&mut result.details, target, transport, preferred_tls.status.as_str());
    }
    result
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
