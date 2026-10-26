use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::describe_http_observation;
use crate::connectivity::adapters::tls::TlsKeyLogCallback;
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{CircumventionTarget, ProbeDetail, ProbeResult, ServiceTarget};

use super::super::endpoint::{
    is_probe_failure, is_server_error, probe_http_url, run_endpoint_probe, run_quic_endpoint_probe,
};
use super::support::append_route_details;

pub fn run_service_probe(
    target: &ServiceTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport, key_log));
    let media = target.media_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport, key_log));
    let gateway = run_endpoint_probe(
        target.tcp_endpoint_host.as_deref(),
        target.tcp_endpoint_ip.as_deref(),
        target.tcp_endpoint_port,
        target.tls_server_name.as_deref().or(target.tcp_endpoint_host.as_deref()),
        transport,
        tls_verifier,
        key_log,
    );
    let quic = run_quic_endpoint_probe(
        target.quic_host.as_deref(),
        target.quic_connect_ip.as_deref(),
        target.quic_port,
        transport,
    );

    let bootstrap_status =
        bootstrap.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = bootstrap.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);
    let media_status = media.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let media_detail = media.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);
    // A failed leg makes the service partial; it is blocked only when the
    // bootstrap itself failed without a server answer. A 3xx or a 4xx/5xx
    // bootstrap means the server responded, which is not censorship, but it
    // is not a healthy bootstrap either, so it is partial.
    let outcome = if is_probe_failure(&bootstrap_status) && !is_server_error(&bootstrap_status) {
        "service_blocked"
    } else if !matches!(bootstrap_status.as_str(), "http_ok" | "not_run")
        || [&media_status, &gateway.status, &quic.status].into_iter().any(|status| is_probe_failure(status))
    {
        "service_partial"
    } else {
        "service_ok"
    };

    let mut result = ProbeResult {
        probe_type: "service_reachability".to_string(),
        target: target.service.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "service".to_string(), value: target.service.clone() },
            ProbeDetail { key: "bootstrapStatus".to_string(), value: bootstrap_status },
            ProbeDetail { key: "bootstrapDetail".to_string(), value: bootstrap_detail },
            ProbeDetail { key: "mediaStatus".to_string(), value: media_status },
            ProbeDetail { key: "mediaDetail".to_string(), value: media_detail },
            ProbeDetail { key: "gatewayStatus".to_string(), value: gateway.status.clone() },
            ProbeDetail { key: "gatewayError".to_string(), value: gateway.error.clone() },
            ProbeDetail { key: "quicStatus".to_string(), value: quic.status.clone() },
            ProbeDetail { key: "quicError".to_string(), value: quic.error.clone() },
        ],
    };
    append_route_details(&mut result.details, "gateway", gateway.local_addr, gateway.route_report.as_ref());
    append_route_details(&mut result.details, "quic", quic.local_addr, quic.route_report.as_ref());
    result
}

pub fn run_circumvention_probe(
    target: &CircumventionTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport, key_log));
    let handshake = run_endpoint_probe(
        target.handshake_host.as_deref(),
        target.handshake_ip.as_deref(),
        target.handshake_port,
        target.tls_server_name.as_deref().or(target.handshake_host.as_deref()),
        transport,
        tls_verifier,
        key_log,
    );
    let initial_bootstrap_status =
        bootstrap.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = bootstrap.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);

    // Retry bootstrap once if it failed, to distinguish transient from consistent
    let (bootstrap_status, circumvention_retry_count) = if is_probe_failure(&initial_bootstrap_status)
        && initial_bootstrap_status != "not_run"
    {
        let retry = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport, key_log));
        let retry_status = retry.as_ref().map_or_else(|| initial_bootstrap_status.clone(), |obs| obs.status.clone());
        (retry_status, 1usize)
    } else {
        (initial_bootstrap_status, 0usize)
    };

    let outcome = if is_probe_failure(&handshake.status) {
        "circumvention_blocked"
    } else if is_probe_failure(&bootstrap_status) {
        if is_server_error(&bootstrap_status) { "circumvention_degraded" } else { "circumvention_blocked" }
    } else {
        "circumvention_ok"
    };
    let mut result = ProbeResult {
        probe_type: "circumvention_reachability".to_string(),
        target: target.tool.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "tool".to_string(), value: target.tool.clone() },
            ProbeDetail { key: "bootstrapStatus".to_string(), value: bootstrap_status },
            ProbeDetail { key: "bootstrapDetail".to_string(), value: bootstrap_detail },
            ProbeDetail { key: "handshakeStatus".to_string(), value: handshake.status.clone() },
            ProbeDetail { key: "handshakeError".to_string(), value: handshake.error.clone() },
            ProbeDetail { key: "probeRetryCount".to_string(), value: circumvention_retry_count.to_string() },
        ],
    };
    append_route_details(&mut result.details, "handshake", handshake.local_addr, handshake.route_report.as_ref());
    result
}

#[cfg(test)]
mod tests {
    use std::net::UdpSocket;

    use super::*;
    use crate::test_fixtures::HttpTextServer;

    fn detail<'a>(result: &'a ProbeResult, key: &str) -> &'a str {
        result.details.iter().find(|detail| detail.key == key).map_or("", |detail| detail.value.as_str())
    }

    // The bootstrap server answered 503 while the QUIC leg failed: the server
    // responded, so the service is partial, not blocked.
    #[test]
    fn server_error_bootstrap_with_a_failed_leg_is_partial() {
        let bootstrap = HttpTextServer::start_text("HTTP/1.1 503 Service Unavailable", "busy");
        let closed_udp_port = UdpSocket::bind("127.0.0.1:0").expect("bind").local_addr().expect("addr").port();
        let target = ServiceTarget {
            id: "svc".to_string(),
            service: "svc".to_string(),
            bootstrap_url: Some(format!("http://127.0.0.1:{}/", bootstrap.port())),
            media_url: None,
            tcp_endpoint_host: None,
            tcp_endpoint_ip: None,
            tcp_endpoint_port: 443,
            tls_server_name: None,
            quic_host: Some("localhost".to_string()),
            quic_connect_ip: Some("127.0.0.1".to_string()),
            quic_port: closed_udp_port,
        };

        let result = run_service_probe(&target, &TransportConfig::Direct { route_experiment: None }, None, None);

        assert_eq!(detail(&result, "bootstrapStatus"), "http_status_503");
        assert_ne!(detail(&result, "quicStatus"), "quic_response");
        assert_eq!(result.outcome, "service_partial");
    }

    // A redirect answer (for example a 302 to an ISP stub page) is not a
    // healthy bootstrap: the service is partial, not ok.
    #[test]
    fn redirect_bootstrap_is_partial() {
        let bootstrap = HttpTextServer::start_text("HTTP/1.1 302 Found", "moved");
        let target = ServiceTarget {
            id: "svc".to_string(),
            service: "svc".to_string(),
            bootstrap_url: Some(format!("http://127.0.0.1:{}/", bootstrap.port())),
            media_url: None,
            tcp_endpoint_host: None,
            tcp_endpoint_ip: None,
            tcp_endpoint_port: 443,
            tls_server_name: None,
            quic_host: None,
            quic_connect_ip: None,
            quic_port: 443,
        };

        let result = run_service_probe(&target, &TransportConfig::Direct { route_experiment: None }, None, None);

        assert_eq!(detail(&result, "bootstrapStatus"), "http_status_302");
        assert_eq!(result.outcome, "service_partial");
    }
}
