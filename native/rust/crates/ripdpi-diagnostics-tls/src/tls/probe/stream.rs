use std::sync::Arc;
use std::time::Instant;

use rustls::client::danger::ServerCertVerifier;

use super::super::key_log::TlsKeyLogCallback;
use super::super::types::{ProbeStreamResult, TlsClientProfile};
use super::capture::capture_tls_handshake;
use crate::cdn_ech::opportunistic_ech_provider_for_ip;
use crate::platform_ttl;
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, connect_transport_observed};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

pub(crate) fn open_probe_stream_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ProbeStreamResult, String> {
    open_probe_stream_targets_with_key_log(
        targets,
        port,
        transport,
        tls_name,
        verify_certificates,
        profile,
        tls_verifier,
        None,
    )
}

pub(crate) fn open_probe_stream_targets_with_key_log(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<ProbeStreamResult, String> {
    let opened = open_transport_stream(targets, port, transport)?;
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    opened.stream.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    opened.stream.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())?;

    match tls_name {
        Some(name) if should_run_tls_handshake(verify_certificates, port, profile) => {
            let captured =
                capture_tls_handshake(opened.stream, targets, transport, name, profile, tls_verifier, key_log)?;
            let local_socket_ttl = match &captured.stream {
                ConnectionStream::Tls(stream) => platform_ttl::get_local_socket_ttl(&stream.sock),
                ConnectionStream::Plain(_) => unreachable!(),
            };

            Ok(ProbeStreamResult {
                stream: captured.stream,
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
) -> Result<OpenedTransportStream, String> {
    let tcp_start = Instant::now();
    let transport_result = connect_transport_observed(targets, port, transport)?;
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

fn build_plain_result(opened: OpenedTransportStream) -> Result<ProbeStreamResult, String> {
    let local_socket_ttl = platform_ttl::get_local_socket_ttl(&opened.stream);

    Ok(ProbeStreamResult {
        stream: ConnectionStream::Plain(opened.stream),
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
