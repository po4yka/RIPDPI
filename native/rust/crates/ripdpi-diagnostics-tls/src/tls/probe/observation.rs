use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;

use crate::transport::ConnectionStream;

use super::super::classification::tls_version_label;
use super::super::types::{EchConfigSource, ProbeStreamError, ProbeStreamResult, TlsClientProfile, TlsObservation};
use super::ech;

struct SuccessObservationParts {
    status: String,
    version: Option<String>,
    error: Option<String>,
    ech_resolution_detail: Option<String>,
    ech_bootstrap_policy: Option<String>,
    ech_bootstrap_resolver_id: Option<String>,
    ech_outer_extension_policy: Option<String>,
    ech_first_flight_plan: Option<String>,
}

pub(crate) fn from_probe_stream_result(result: ProbeStreamResult, profile: TlsClientProfile) -> TlsObservation {
    let transport_open_ms = Some(result.transport_open_ms);
    let tls_handshake_ms = Some(result.tls_handshake_ms);
    let cert_chain_length = result.cert_chain_length;
    let cert_issuer = result.cert_issuer;
    let local_socket_ttl = result.local_socket_ttl;
    let ja3_fingerprint = result.ja3_fingerprint;
    let client_hello_capture = result.client_hello_capture;
    let connected_addr = result.connected_addr;
    let local_addr = result.local_addr;
    let cdn_provider = result.cdn_provider;
    let route_report = result.route_report;
    let tls_template_first_flight_plan = result.tls_template_first_flight_plan;
    let ech_config_source = result.ech_config_source;
    let ech_status = match &result.stream {
        ConnectionStream::Tls(stream) if ech_config_source.is_some() => Some(stream.conn.ech_status()),
        _ => None,
    };
    let mut stream = result.stream;
    let mapped = map_success_observation(&mut stream, profile, tls_template_first_flight_plan.as_ref());
    stream.shutdown();

    TlsObservation {
        status: mapped.status,
        version: mapped.version,
        error: mapped.error,
        failure_stage: None,
        failure_duration_ms: None,
        certificate_anomaly: false,
        ech_resolution_detail: mapped.ech_resolution_detail,
        ech_config_source,
        ech_status,
        ech_initial_attempt: result.ech_initial_attempt.map(|value| *value),
        ech_retry_skipped_reason: None,
        ech_bootstrap_policy: mapped.ech_bootstrap_policy,
        ech_bootstrap_resolver_id: if ech_config_source == Some(EchConfigSource::DnsHttps) {
            mapped.ech_bootstrap_resolver_id
        } else {
            None
        },
        ech_outer_extension_policy: mapped.ech_outer_extension_policy,
        ech_first_flight_plan: mapped.ech_first_flight_plan,
        transport_open_ms,
        tls_handshake_ms,
        cert_chain_length,
        cert_issuer,
        local_socket_ttl,
        ja3_fingerprint,
        client_hello_capture,
        tls_alert_code: None,
        tls_alert_description: None,
        tls_server_hello_received: Some(true),
        tls_peer_response_after_client_hello: Some(true),
        connected_addr,
        local_addr,
        cdn_provider,
        route_report,
    }
}

pub(crate) fn from_probe_error(mut err: ProbeStreamError) -> TlsObservation {
    let failure_stage = err.stage;
    let failure_duration_ms = err.duration_ms;
    let certificate_anomaly = err.rejected_server_certificate();
    let client_hello = err.client_hello.take().map_or_else(Default::default, |evidence| *evidence);
    let ech = err.ech.take();
    let ech_config_source = ech.as_ref().and_then(|evidence| evidence.config_source);
    let ech_status = ech.as_ref().and_then(|evidence| evidence.status);
    let ech_retry_skipped_reason = ech.as_ref().and_then(|evidence| evidence.retry_skipped_reason);
    let (ech_initial_attempt, lookup_failure) = match ech {
        Some(evidence) => (evidence.initial_attempt.map(|attempt| *attempt), evidence.lookup_failure),
        None => (None, None),
    };
    let mut lookup_outcome = lookup_failure.as_ref().map(ech::from_resolution_error);
    let status = if lookup_outcome.is_some() {
        "not_run"
    } else if certificate_anomaly {
        "tls_cert_invalid"
    } else {
        "tls_handshake_failed"
    };
    let resolution_detail = lookup_outcome
        .as_mut()
        .and_then(|outcome| outcome.resolution_detail.take())
        .or_else(|| ech_config_source.map(|_| "ech_config_available".to_string()));
    let (bootstrap_policy, bootstrap_resolver_id, outer_extension_policy) = lookup_outcome
        .map_or((None, None, None), |outcome| {
            (outcome.bootstrap_policy, outcome.bootstrap_resolver_id, outcome.outer_extension_policy)
        });
    TlsObservation {
        status: status.to_string(),
        version: None,
        error: Some(err.message),
        failure_stage: Some(failure_stage),
        failure_duration_ms: Some(failure_duration_ms),
        certificate_anomaly,
        ech_resolution_detail: resolution_detail,
        ech_config_source,
        ech_status,
        ech_initial_attempt,
        ech_retry_skipped_reason,
        ech_bootstrap_policy: bootstrap_policy,
        ech_bootstrap_resolver_id: bootstrap_resolver_id,
        ech_outer_extension_policy: outer_extension_policy,
        ech_first_flight_plan: None,
        transport_open_ms: err.transport_open_ms,
        tls_handshake_ms: err.tls_handshake_ms,
        cert_chain_length: None,
        cert_issuer: None,
        local_socket_ttl: None,
        ja3_fingerprint: client_hello.ja3_fingerprint,
        client_hello_capture: client_hello.capture,
        tls_alert_code: err.tls_alert.map(u8::from),
        tls_alert_description: err.tls_alert.and_then(|alert| alert.as_str()).map(str::to_string),
        tls_server_hello_received: err.tls_server_hello_received,
        tls_peer_response_after_client_hello: err.peer_response_after_client_hello,
        connected_addr: err.connected_addr,
        local_addr: None,
        cdn_provider: None,
        route_report: None,
    }
}

fn map_success_observation(
    stream: &mut ConnectionStream,
    profile: TlsClientProfile,
    plan: Option<&TlsTemplateFirstFlightPlan>,
) -> SuccessObservationParts {
    match stream {
        ConnectionStream::Plain(_) => SuccessObservationParts {
            status: "tls_ok".to_string(),
            version: None,
            error: None,
            ech_resolution_detail: None,
            ech_bootstrap_policy: None,
            ech_bootstrap_resolver_id: None,
            ech_outer_extension_policy: None,
            ech_first_flight_plan: None,
        },
        ConnectionStream::Tls(stream) => {
            let version = tls_version_label(stream.conn.protocol_version());
            if matches!(profile, TlsClientProfile::Tls13WithEch) {
                let outcome = ech::from_accepted_status(stream.conn.ech_status(), plan);
                SuccessObservationParts {
                    status: outcome.status,
                    version,
                    error: outcome.error,
                    ech_resolution_detail: outcome.resolution_detail,
                    ech_bootstrap_policy: outcome.bootstrap_policy,
                    ech_bootstrap_resolver_id: outcome.bootstrap_resolver_id,
                    ech_outer_extension_policy: outcome.outer_extension_policy,
                    ech_first_flight_plan: outcome.first_flight_plan,
                }
            } else {
                SuccessObservationParts {
                    status: "tls_ok".to_string(),
                    version,
                    error: None,
                    ech_resolution_detail: None,
                    ech_bootstrap_policy: None,
                    ech_bootstrap_resolver_id: None,
                    ech_outer_extension_policy: None,
                    ech_first_flight_plan: None,
                }
            }
        }
    }
}
