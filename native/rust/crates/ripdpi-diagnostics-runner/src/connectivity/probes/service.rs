use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::describe_http_observation;
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
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
    let media = target.media_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
    let gateway = run_endpoint_probe(
        target.tcp_endpoint_host.as_deref(),
        target.tcp_endpoint_ip.as_deref(),
        target.tcp_endpoint_port,
        target.tls_server_name.as_deref().or(target.tcp_endpoint_host.as_deref()),
        transport,
        tls_verifier,
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
    let outcome = if is_probe_failure(&bootstrap_status)
        || is_probe_failure(&media_status)
        || is_probe_failure(&gateway.status)
        || is_probe_failure(&quic.status)
    {
        if bootstrap_status == "http_ok"
            && media_status == "http_ok"
            && matches!(gateway.status.as_str(), "not_run" | "tls_ok" | "tcp_connect_ok")
            && matches!(quic.status.as_str(), "not_run" | "quic_initial_response" | "quic_response")
        {
            "service_ok"
        } else if bootstrap_status != "not_run" && bootstrap_status != "http_ok" {
            "service_blocked"
        } else {
            "service_partial"
        }
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
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
    let handshake = run_endpoint_probe(
        target.handshake_host.as_deref(),
        target.handshake_ip.as_deref(),
        target.handshake_port,
        target.tls_server_name.as_deref().or(target.handshake_host.as_deref()),
        transport,
        tls_verifier,
    );
    let initial_bootstrap_status =
        bootstrap.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = bootstrap.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);

    // Retry bootstrap once if it failed, to distinguish transient from consistent
    let (bootstrap_status, circumvention_retry_count) = if is_probe_failure(&initial_bootstrap_status)
        && initial_bootstrap_status != "not_run"
    {
        let retry = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
        let retry_status = retry.as_ref().map_or_else(|| initial_bootstrap_status.clone(), |obs| obs.status.clone());
        (retry_status, 1usize)
    } else {
        (initial_bootstrap_status, 0usize)
    };

    let outcome = if is_probe_failure(&handshake.status) {
        "circumvention_blocked"
    } else if is_probe_failure(&bootstrap_status) {
        if is_server_error(&bootstrap_status) {
            "circumvention_degraded"
        } else {
            "circumvention_blocked"
        }
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
