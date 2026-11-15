use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{HttpObservation, describe_http_observation, is_blockpage};
use crate::connectivity::adapters::tls::TlsKeyLogCallback;
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{CircumventionTarget, ProbeDetail, ProbeResult, ServiceTarget};

use super::super::endpoint::{endpoint_stop_reason, probe_http_url, run_endpoint_probe, run_quic_endpoint_probe};
use super::support::append_route_details;

pub fn run_service_probe(
    target: &ServiceTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    cancel: &AtomicBool,
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, transport, key_log, cancel));
    let media = target.media_url.as_ref().map(|url| probe_http_url(url, transport, key_log, cancel));
    let gateway = run_endpoint_probe(
        target.tcp_endpoint_host.as_deref(),
        target.tcp_endpoint_ip.as_deref(),
        target.tcp_endpoint_port,
        target.tls_server_name.as_deref().or(target.tcp_endpoint_host.as_deref()),
        transport,
        tls_verifier,
        key_log,
        cancel,
    );
    let quic = run_quic_endpoint_probe(
        target.quic_host.as_deref(),
        target.quic_connect_ip.as_deref(),
        target.quic_port,
        transport,
        cancel,
    );

    let bootstrap_status =
        bootstrap.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = bootstrap.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);
    let media_status = media.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let media_detail = media.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);
    let legs =
        [http_leg(bootstrap.as_ref()), http_leg(media.as_ref()), socket_leg(&gateway.status), socket_leg(&quic.status)];
    let outcome = match endpoint_evidence(&legs) {
        EndpointEvidence::Reached => "service_ok",
        EndpointEvidence::Partial => "service_partial",
        EndpointEvidence::Blockpage => "service_blocked",
        EndpointEvidence::Inconclusive => "inconclusive",
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
    cancel: &AtomicBool,
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, transport, key_log, cancel));
    let handshake = run_endpoint_probe(
        target.handshake_host.as_deref(),
        target.handshake_ip.as_deref(),
        target.handshake_port,
        target.tls_server_name.as_deref().or(target.handshake_host.as_deref()),
        transport,
        tls_verifier,
        key_log,
        cancel,
    );
    // Retry an observed failure once while the scan still allows network work.
    let retry = bootstrap
        .as_ref()
        .filter(|first| {
            matches!(http_leg(Some(first)), EndpointLeg::Failed | EndpointLeg::Blockpage)
                || first.response.as_ref().is_some_and(|response| response.status_code >= 400)
        })
        .filter(|_| endpoint_stop_reason(cancel).is_none())
        .and(target.bootstrap_url.as_deref())
        .map(|url| probe_http_url(url, transport, key_log, cancel));
    let circumvention_retry_count = usize::from(retry.is_some());
    let final_bootstrap = retry.as_ref().or(bootstrap.as_ref());
    let bootstrap_status =
        final_bootstrap.map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = final_bootstrap.map_or_else(|| "not_run".to_string(), describe_http_observation);
    let outcome = match endpoint_evidence(&[http_leg(final_bootstrap), socket_leg(&handshake.status)]) {
        EndpointEvidence::Reached => "circumvention_ok",
        EndpointEvidence::Partial => "circumvention_degraded",
        EndpointEvidence::Blockpage => "circumvention_blocked",
        EndpointEvidence::Inconclusive => "inconclusive",
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
    if retry.is_some()
        && let Some(initial) = bootstrap
    {
        result.details.extend([
            ProbeDetail { key: "bootstrapInitialDetail".into(), value: describe_http_observation(&initial) },
            ProbeDetail { key: "bootstrapInitialStatus".into(), value: initial.status },
        ]);
    }
    append_route_details(&mut result.details, "handshake", handshake.local_addr, handshake.route_report.as_ref());
    result
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum EndpointLeg {
    Skipped,
    Incomplete,
    Failed,
    Responded,
    Reached,
    Blockpage,
}

enum EndpointEvidence {
    Inconclusive,
    Partial,
    Reached,
    Blockpage,
}

fn http_leg(observation: Option<&HttpObservation>) -> EndpointLeg {
    match observation {
        None => EndpointLeg::Skipped,
        Some(observation) if is_blockpage(observation) => EndpointLeg::Blockpage,
        Some(observation) => match &observation.response {
            Some(response) if (200..300).contains(&response.status_code) => EndpointLeg::Reached,
            Some(_) => EndpointLeg::Responded,
            None if matches!(observation.status.as_str(), "interrupted" | "invalid_target") => EndpointLeg::Incomplete,
            None => EndpointLeg::Failed,
        },
    }
}

fn socket_leg(status: &str) -> EndpointLeg {
    match status {
        "not_run" => EndpointLeg::Skipped,
        "interrupted" | "invalid_target" => EndpointLeg::Incomplete,
        "tls_ok" | "tcp_connect_ok" | "quic_initial_response" => EndpointLeg::Reached,
        "quic_initial_ack"
        | "quic_initial_other"
        | "quic_initial_close"
        | "quic_retry"
        | "quic_version_negotiation" => EndpointLeg::Responded,
        _ => EndpointLeg::Failed,
    }
}

fn endpoint_evidence(legs: &[EndpointLeg]) -> EndpointEvidence {
    if legs.contains(&EndpointLeg::Blockpage) {
        EndpointEvidence::Blockpage
    } else if legs.contains(&EndpointLeg::Reached)
        && legs.iter().all(|leg| matches!(leg, EndpointLeg::Reached | EndpointLeg::Skipped))
    {
        EndpointEvidence::Reached
    } else if legs.iter().any(|leg| matches!(leg, EndpointLeg::Reached | EndpointLeg::Responded)) {
        EndpointEvidence::Partial
    } else {
        EndpointEvidence::Inconclusive
    }
}

#[cfg(test)]
mod tests;
