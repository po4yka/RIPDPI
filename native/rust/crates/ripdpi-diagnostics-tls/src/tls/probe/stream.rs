use super::super::types::{
    ProbeStreamError, ProbeStreamFailureStage, ProbeStreamOptions, ProbeStreamResult, TlsClientProfile,
};
use super::capture::{CaptureTlsHandshakeOptions, capture_tls_handshake};
use crate::cdn_ech::opportunistic_ech_provider_for_ip;
use crate::platform_ttl;
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, connect_transport_observed};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};
use std::time::Instant;

pub(crate) fn open_probe_stream_targets_with_options(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    options: &ProbeStreamOptions<'_>,
) -> Result<ProbeStreamResult, ProbeStreamError> {
    open_probe_stream_targets_with_options_and_abort(targets, port, transport, tls_name, options, &|| None)
}

pub(crate) fn open_probe_stream_targets_with_options_and_abort(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    options: &ProbeStreamOptions<'_>,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> Result<ProbeStreamResult, ProbeStreamError> {
    let started = Instant::now();
    if let Some(reason) = should_abort() {
        return Err(stream_error(
            ProbeStreamFailureStage::StreamSetup,
            format!("probe_aborted:{reason}"),
            started,
            None,
        ));
    }
    let opened = open_transport_stream(targets, port, transport, started)?;
    if let Some(reason) = should_abort() {
        return Err(stream_error(
            ProbeStreamFailureStage::StreamSetup,
            format!("probe_aborted:{reason}"),
            started,
            Some(&opened),
        ));
    }
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT)
        .map_err(|err| stream_error(ProbeStreamFailureStage::StreamSetup, err, started, Some(&opened)))?;
    opened
        .stream
        .set_read_timeout(Some(timeout))
        .map_err(|err| stream_error(ProbeStreamFailureStage::StreamSetup, err, started, Some(&opened)))?;
    opened
        .stream
        .set_write_timeout(Some(timeout))
        .map_err(|err| stream_error(ProbeStreamFailureStage::StreamSetup, err, started, Some(&opened)))?;

    match tls_name {
        Some(name) if should_run_tls_handshake(options.verify_certificates, port, options.profile) => {
            let tcp_connect_ms = opened.tcp_connect_ms;
            let connected_addr = opened.connected_addr;
            let captured = capture_tls_handshake(
                opened.stream,
                &CaptureTlsHandshakeOptions {
                    targets,
                    transport,
                    tls_name: name,
                    profile: options.profile,
                    tls_verifier: options.tls_verifier,
                    key_log: options.key_log,
                    application_protocol: options.application_protocol,
                    should_abort,
                },
            )
            .map_err(|err| ProbeStreamError {
                stage: ProbeStreamFailureStage::TlsHandshake,
                message: err,
                duration_ms: started.elapsed().as_millis().max(1) as u64,
                tcp_connect_ms: Some(tcp_connect_ms),
                connected_addr,
            })?;
            let local_socket_ttl = match &captured.stream {
                ConnectionStream::Tls(stream) => platform_ttl::get_local_socket_ttl(&stream.sock),
                ConnectionStream::Plain(_) => unreachable!(),
            };

            Ok(ProbeStreamResult {
                stream: captured.stream,
                negotiated_alpn: captured.negotiated_alpn,
                tls_template_first_flight_plan: captured.tls_template_first_flight_plan,
                tcp_connect_ms: opened.tcp_connect_ms,
                tls_handshake_ms: captured.tls_handshake_ms,
                cert_chain_length: captured.cert_chain_length,
                cert_issuer: captured.cert_issuer,
                local_socket_ttl,
                ja3_fingerprint: captured.ja3_fingerprint,
                connected_addr: opened.connected_addr,
                local_addr: opened.local_addr,
                cdn_provider: opened.cdn_provider,
                route_report: opened.route_report,
            })
        }
        _ => build_plain_result(opened),
    }
}

struct OpenedTransportStream {
    stream: std::net::TcpStream,
    tcp_connect_ms: u64,
    connected_addr: Option<std::net::SocketAddr>,
    local_addr: Option<std::net::SocketAddr>,
    cdn_provider: Option<String>,
    route_report: Option<crate::transport::RouteExperimentReport>,
}

fn open_transport_stream(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    started: Instant,
) -> Result<OpenedTransportStream, ProbeStreamError> {
    let tcp_start = Instant::now();
    let transport_result = connect_transport_observed(targets, port, transport).map_err(|err| ProbeStreamError {
        stage: err.stage.into(),
        message: err.message,
        duration_ms: started.elapsed().as_millis().max(1) as u64,
        tcp_connect_ms: None,
        connected_addr: None,
    })?;
    let tcp_connect_ms = tcp_start.elapsed().as_millis() as u64;
    let connected_addr = transport_result.connected_addr;
    let cdn_provider = connected_addr.and_then(|addr| opportunistic_ech_provider_for_ip(addr.ip()).map(str::to_string));

    Ok(OpenedTransportStream {
        stream: transport_result.stream,
        tcp_connect_ms,
        connected_addr,
        local_addr: transport_result.local_addr,
        cdn_provider,
        route_report: transport_result.route_report,
    })
}

fn should_run_tls_handshake(verify_certificates: bool, port: u16, profile: TlsClientProfile) -> bool {
    verify_certificates || port == 443 || !matches!(profile, TlsClientProfile::Auto)
}

fn build_plain_result(opened: OpenedTransportStream) -> Result<ProbeStreamResult, ProbeStreamError> {
    let local_socket_ttl = platform_ttl::get_local_socket_ttl(&opened.stream);

    Ok(ProbeStreamResult {
        stream: ConnectionStream::Plain(opened.stream),
        negotiated_alpn: None,
        tls_template_first_flight_plan: None,
        tcp_connect_ms: opened.tcp_connect_ms,
        tls_handshake_ms: 0,
        cert_chain_length: None,
        cert_issuer: None,
        local_socket_ttl,
        ja3_fingerprint: None,
        connected_addr: opened.connected_addr,
        local_addr: opened.local_addr,
        cdn_provider: opened.cdn_provider,
        route_report: opened.route_report,
    })
}

fn stream_error(
    stage: ProbeStreamFailureStage,
    error: impl std::fmt::Display,
    started: Instant,
    opened: Option<&OpenedTransportStream>,
) -> ProbeStreamError {
    ProbeStreamError {
        stage,
        message: error.to_string(),
        duration_ms: started.elapsed().as_millis().max(1) as u64,
        tcp_connect_ms: opened.map(|value| value.tcp_connect_ms),
        connected_addr: opened.and_then(|value| value.connected_addr),
    }
}
