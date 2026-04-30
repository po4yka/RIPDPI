use std::sync::Arc;
use std::time::Instant;

use ripdpi_tls_profiles::plan_first_flight;
use rustls::client::danger::ServerCertVerifier;
use rustls::client::EchStatus;
use rustls::{ClientConnection, StreamOwned};

use super::certs::extract_cert_info;
use super::classification::{
    classify_tls_dpi_signature, ech_status_label, first_flight_plan_label, is_certificate_error,
    parse_alert_from_error, tls_version_label,
};
use super::config::{
    build_ech_client_config, build_standard_client_config, make_server_name, planned_tls_template_metadata,
    planned_tls_template_profile, ECH_CONFIG_UNAVAILABLE_ERROR,
};
use super::types::{ProbeStreamResult, TlsClientProfile, TlsObservation};
use crate::cdn_ech::opportunistic_ech_provider_for_ip;
use crate::ja3::{self, RecordingStream};
use crate::platform_ttl;
use crate::transport::{connect_transport_observed, ConnectionStream, TargetAddress, TransportConfig};
use crate::util::IO_TIMEOUT;

pub fn try_tls_handshake(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> TlsObservation {
    try_tls_handshake_targets(
        std::slice::from_ref(target),
        port,
        transport,
        server_name,
        verify_certificates,
        profile,
        tls_verifier,
    )
}

pub fn try_tls_handshake_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> TlsObservation {
    match open_probe_stream_targets(
        targets,
        port,
        transport,
        Some(server_name),
        verify_certificates,
        profile,
        tls_verifier,
    ) {
        Ok(result) => {
            let tcp_connect_ms = Some(result.tcp_connect_ms);
            let tls_handshake_ms = Some(result.tls_handshake_ms);
            let cert_chain_length = result.cert_chain_length;
            let cert_issuer = result.cert_issuer;
            let observed_server_ttl = result.observed_server_ttl;
            let estimated_hop_count = result.estimated_hop_count;
            let ja3_fingerprint = result.ja3_fingerprint;
            let connected_addr = result.connected_addr;
            let local_addr = result.local_addr;
            let cdn_provider = result.cdn_provider;
            let route_report = result.route_report;
            let tls_template_first_flight_plan = result.tls_template_first_flight_plan;
            let mut stream = result.stream;
            let (
                status,
                version,
                error,
                ech_resolution_detail,
                ech_bootstrap_policy,
                ech_bootstrap_resolver_id,
                ech_outer_extension_policy,
                ech_first_flight_plan,
            ) = match &mut stream {
                ConnectionStream::Plain(_) => ("tls_ok".to_string(), None, None, None, None, None, None, None),
                ConnectionStream::Tls(stream) => {
                    let version = tls_version_label(stream.conn.protocol_version());
                    if matches!(profile, TlsClientProfile::Tls13WithEch) {
                        let ech_status = stream.conn.ech_status();
                        let plan = tls_template_first_flight_plan.as_ref();
                        if matches!(ech_status, EchStatus::Accepted) {
                            (
                                "tls_ok".to_string(),
                                version,
                                None,
                                Some("ech_config_available".to_string()),
                                plan.map(|value| value.ech_bootstrap_policy.to_string()),
                                plan.and_then(|value| value.ech_bootstrap_resolver_id.map(ToString::to_string)),
                                plan.map(|value| value.ech_outer_extension_policy.to_string()),
                                plan.map(first_flight_plan_label),
                            )
                        } else {
                            (
                                "tls_handshake_failed".to_string(),
                                version,
                                Some(format!("ech_{}", ech_status_label(ech_status))),
                                Some("ech_config_available".to_string()),
                                plan.map(|value| value.ech_bootstrap_policy.to_string()),
                                plan.and_then(|value| value.ech_bootstrap_resolver_id.map(ToString::to_string)),
                                plan.map(|value| value.ech_outer_extension_policy.to_string()),
                                plan.map(first_flight_plan_label),
                            )
                        }
                    } else {
                        ("tls_ok".to_string(), version, None, None, None, None, None, None)
                    }
                }
            };
            stream.shutdown();
            TlsObservation {
                status,
                version,
                error,
                certificate_anomaly: false,
                ech_resolution_detail,
                ech_bootstrap_policy,
                ech_bootstrap_resolver_id,
                ech_outer_extension_policy,
                ech_first_flight_plan,
                tcp_connect_ms,
                tls_handshake_ms,
                cert_chain_length,
                cert_issuer,
                observed_server_ttl,
                estimated_hop_count,
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
        Err(err) => {
            if matches!(profile, TlsClientProfile::Tls13WithEch)
                && (err == ECH_CONFIG_UNAVAILABLE_ERROR || err.starts_with("ech_resolution_failed:"))
            {
                let ech_resolution_detail =
                    if err == ECH_CONFIG_UNAVAILABLE_ERROR { "ech_not_published".to_string() } else { err.clone() };
                return TlsObservation {
                    status: "not_run".to_string(),
                    version: None,
                    error: Some(err),
                    certificate_anomaly: false,
                    ech_resolution_detail: Some(ech_resolution_detail),
                    ech_bootstrap_policy: Some(
                        planned_tls_template_metadata(TlsClientProfile::Tls13WithEch)
                            .template
                            .ech_bootstrap_policy
                            .to_string(),
                    ),
                    ech_bootstrap_resolver_id: planned_tls_template_metadata(TlsClientProfile::Tls13WithEch)
                        .template
                        .ech_bootstrap_resolver_id
                        .map(ToString::to_string),
                    ech_outer_extension_policy: Some(
                        planned_tls_template_metadata(TlsClientProfile::Tls13WithEch)
                            .template
                            .ech_outer_extension_policy
                            .to_string(),
                    ),
                    ech_first_flight_plan: None,
                    tcp_connect_ms: None,
                    tls_handshake_ms: None,
                    cert_chain_length: None,
                    cert_issuer: None,
                    observed_server_ttl: None,
                    estimated_hop_count: None,
                    ja3_fingerprint: None,
                    tls_alert_code: None,
                    tls_alert_description: None,
                    tls_server_hello_received: None,
                    tls_dpi_signature: None,
                    connected_addr: None,
                    local_addr: None,
                    cdn_provider: None,
                    route_report: None,
                };
            }
            let certificate_anomaly = is_certificate_error(&err);
            let (tls_alert_code, tls_alert_description) = parse_alert_from_error(&err);
            let tls_server_hello_received = Some(err.contains("AlertReceived"));
            let tls_dpi_signature = tls_alert_code.and_then(|code| classify_tls_dpi_signature(code, None));
            TlsObservation {
                status: if certificate_anomaly {
                    "tls_cert_invalid".to_string()
                } else {
                    "tls_handshake_failed".to_string()
                },
                version: None,
                error: Some(err),
                certificate_anomaly,
                ech_resolution_detail: None,
                ech_bootstrap_policy: None,
                ech_bootstrap_resolver_id: None,
                ech_outer_extension_policy: None,
                ech_first_flight_plan: None,
                tcp_connect_ms: None,
                tls_handshake_ms: None,
                cert_chain_length: None,
                cert_issuer: None,
                observed_server_ttl: None,
                estimated_hop_count: None,
                ja3_fingerprint: None,
                tls_alert_code,
                tls_alert_description,
                tls_server_hello_received,
                tls_dpi_signature,
                connected_addr: None,
                local_addr: None,
                cdn_provider: None,
                route_report: None,
            }
        }
    }
}

pub fn open_probe_stream(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ProbeStreamResult, String> {
    open_probe_stream_targets(
        std::slice::from_ref(target),
        port,
        transport,
        tls_name,
        verify_certificates,
        profile,
        tls_verifier,
    )
}

pub fn open_probe_stream_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ProbeStreamResult, String> {
    let tcp_start = Instant::now();
    let transport_result = connect_transport_observed(targets, port, transport)?;
    let tcp_connect_ms = tcp_start.elapsed().as_millis() as u64;
    let connected_addr = transport_result.connected_addr;
    let local_addr = transport_result.local_addr;
    let cdn_provider = connected_addr.and_then(|addr| opportunistic_ech_provider_for_ip(addr.ip()).map(str::to_string));
    let route_report = transport_result.route_report;
    let socket = transport_result.stream;

    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;

    match tls_name {
        Some(name) if verify_certificates || port == 443 || !matches!(profile, TlsClientProfile::Auto) => {
            let target = targets.first().ok_or_else(|| "no_tls_targets".to_string())?;
            let template_profile = planned_tls_template_profile(profile);
            let config = match profile {
                TlsClientProfile::Tls13WithEch => build_ech_client_config(name, target, transport, tls_verifier)?,
                _ => build_standard_client_config(profile, tls_verifier),
            };
            let server_name = make_server_name(name, target)?;
            let mut connection = ClientConnection::new(config, server_name).map_err(|err| err.to_string())?;

            // Wrap the socket in RecordingStream to capture the ClientHello for JA3.
            let mut recording = RecordingStream::new(socket);

            let tls_start = Instant::now();
            while connection.is_handshaking() {
                connection.complete_io(&mut recording).map_err(|err| err.to_string())?;
            }
            let tls_handshake_ms = tls_start.elapsed().as_millis() as u64;

            // Compute JA3 from the recorded outbound bytes before unwrapping.
            let ja3_fingerprint = ja3::compute_ja3(recording.recorded_writes());
            let tls_template_first_flight_plan = plan_first_flight(template_profile, recording.recorded_writes());

            let (socket, _recorded) = recording.into_parts();
            let tls_stream = StreamOwned::new(connection, socket);

            let (cert_chain_length, cert_issuer) = extract_cert_info(&tls_stream.conn);

            // Capture server TTL from the underlying TCP socket after TLS handshake
            let observed_server_ttl = platform_ttl::get_observed_ttl(&tls_stream.sock);
            let estimated_hop_count = observed_server_ttl.map(platform_ttl::estimate_hop_count);

            Ok(ProbeStreamResult {
                stream: ConnectionStream::Tls(Box::new(tls_stream)),
                tls_template_first_flight_plan,
                tcp_connect_ms,
                tls_handshake_ms,
                cert_chain_length,
                cert_issuer,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint,
                connected_addr,
                local_addr,
                cdn_provider,
                route_report,
            })
        }
        _ => {
            let observed_server_ttl = platform_ttl::get_observed_ttl(&socket);
            let estimated_hop_count = observed_server_ttl.map(platform_ttl::estimate_hop_count);
            Ok(ProbeStreamResult {
                stream: ConnectionStream::Plain(socket),
                tls_template_first_flight_plan: None,
                tcp_connect_ms,
                tls_handshake_ms: 0,
                cert_chain_length: None,
                cert_issuer: None,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint: None,
                connected_addr,
                local_addr,
                cdn_provider,
                route_report,
            })
        }
    }
}
