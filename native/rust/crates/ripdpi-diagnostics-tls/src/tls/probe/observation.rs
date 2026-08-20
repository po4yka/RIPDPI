use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;

use crate::transport::ConnectionStream;

use super::super::classification::{
    classify_tls_dpi_signature, is_certificate_error, parse_alert_from_error, tls_version_label,
};
use super::super::types::{ProbeStreamError, ProbeStreamResult, TlsClientProfile, TlsObservation};
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
    let tcp_connect_ms = Some(result.tcp_connect_ms);
    let tls_handshake_ms = Some(result.tls_handshake_ms);
    let cert_chain_length = result.cert_chain_length;
    let cert_issuer = result.cert_issuer;
    let local_socket_ttl = result.local_socket_ttl;
    let ja3_fingerprint = result.ja3_fingerprint;
    let connected_addr = result.connected_addr;
    let local_addr = result.local_addr;
    let cdn_provider = result.cdn_provider;
    let route_report = result.route_report;
    let tls_template_first_flight_plan = result.tls_template_first_flight_plan;
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
        ech_bootstrap_policy: mapped.ech_bootstrap_policy,
        ech_bootstrap_resolver_id: mapped.ech_bootstrap_resolver_id,
        ech_outer_extension_policy: mapped.ech_outer_extension_policy,
        ech_first_flight_plan: mapped.ech_first_flight_plan,
        tcp_connect_ms,
        tls_handshake_ms,
        cert_chain_length,
        cert_issuer,
        local_socket_ttl,
        ja3_fingerprint,
        tls_alert_code: None,
        tls_alert_description: None,
        tls_server_hello_received: Some(true),
        tls_dpi_signature: None,
        connected_addr,
        local_addr,
        cdn_provider,
        route_report,
    }
}

pub(crate) fn from_probe_error(err: ProbeStreamError, profile: TlsClientProfile) -> TlsObservation {
    let failure_stage = err.stage;
    let failure_duration_ms = err.duration_ms;
    if let Some(outcome) = ech::from_resolution_error(&err.message, profile) {
        return TlsObservation {
            status: outcome.status,
            version: None,
            error: outcome.error,
            failure_stage: Some(failure_stage),
            failure_duration_ms: Some(failure_duration_ms),
            certificate_anomaly: false,
            ech_resolution_detail: outcome.resolution_detail,
            ech_bootstrap_policy: outcome.bootstrap_policy,
            ech_bootstrap_resolver_id: outcome.bootstrap_resolver_id,
            ech_outer_extension_policy: outcome.outer_extension_policy,
            ech_first_flight_plan: outcome.first_flight_plan,
            tcp_connect_ms: err.tcp_connect_ms,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            local_socket_ttl: None,
            ja3_fingerprint: None,
            tls_alert_code: None,
            tls_alert_description: None,
            tls_server_hello_received: None,
            tls_dpi_signature: None,
            connected_addr: err.connected_addr,
            local_addr: None,
            cdn_provider: None,
            route_report: None,
        };
    }

    let certificate_anomaly = is_certificate_error(&err.message);
    let (tls_alert_code, tls_alert_description) = parse_alert_from_error(&err.message);
    let tls_server_hello_received = Some(err.message.contains("AlertReceived"));
    let tls_dpi_signature = tls_alert_code.and_then(|code| classify_tls_dpi_signature(code, None));

    TlsObservation {
        status: if certificate_anomaly { "tls_cert_invalid" } else { "tls_handshake_failed" }.to_string(),
        version: None,
        error: Some(err.message),
        failure_stage: Some(failure_stage),
        failure_duration_ms: Some(failure_duration_ms),
        certificate_anomaly,
        ech_resolution_detail: None,
        ech_bootstrap_policy: None,
        ech_bootstrap_resolver_id: None,
        ech_outer_extension_policy: None,
        ech_first_flight_plan: None,
        tcp_connect_ms: err.tcp_connect_ms,
        tls_handshake_ms: None,
        cert_chain_length: None,
        cert_issuer: None,
        local_socket_ttl: None,
        ja3_fingerprint: None,
        tls_alert_code,
        tls_alert_description,
        tls_server_hello_received,
        tls_dpi_signature,
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
