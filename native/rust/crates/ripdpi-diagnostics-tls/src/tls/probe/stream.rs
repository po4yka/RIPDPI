use super::super::types::{
    ClientHelloCapture, ProbeStreamError, ProbeStreamFailureStage, ProbeStreamOptions, ProbeStreamResult,
};
use super::capture::CaptureTlsHandshakeOptions;
use super::retry::capture_with_ech_retry;
use crate::platform_ttl;
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, connect_transport_observed_with_abort};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};
use ripdpi_diagnostics_dns::cdn_ech::opportunistic_ech_provider_for_ip;
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
    let opened = open_transport_stream(targets, port, transport, started, should_abort)?;
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
        Some(name) => {
            let transport_open_ms = opened.transport_open_ms;
            let connected_addr = opened.connected_addr;
            let captured = capture_with_ech_retry(
                opened.stream,
                connected_addr,
                &CaptureTlsHandshakeOptions {
                    targets,
                    ech_retry_configs: None,
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
                message: err.message,
                duration_ms: started.elapsed().as_millis().max(1) as u64,
                transport_open_ms: Some(err.reconnect_open_ms.unwrap_or(transport_open_ms)),
                connected_addr,
                tls_server_hello_received: err.server_hello_received,
                peer_response_after_client_hello: err.peer_response_after_client_hello,
                tls_alert: err.alert,
                certificate_anomaly: err.certificate_anomaly,
                tls_handshake_ms: err.handshake_ms,
                client_hello: err.client_hello,
            })?;
            let local_addr = match &captured.stream {
                ConnectionStream::Tls(stream) => stream.sock.local_addr().ok(),
                ConnectionStream::Plain(stream) => stream.local_addr().ok(),
            };
            let local_socket_ttl = match &captured.stream {
                ConnectionStream::Tls(stream) => platform_ttl::get_local_socket_ttl(&stream.sock),
                ConnectionStream::Plain(_) => unreachable!(),
            };

            let (transport_open_ms, route_report) =
                captured.reconnect.map_or((opened.transport_open_ms, opened.route_report), |transport| {
                    (transport.open_ms, transport.route_report)
                });
            Ok(ProbeStreamResult {
                stream: captured.stream,
                negotiated_alpn: captured.negotiated_alpn,
                tls_template_first_flight_plan: captured.tls_template_first_flight_plan,
                transport_open_ms,
                tls_handshake_ms: captured.tls_handshake_ms,
                cert_chain_length: captured.cert_chain_length,
                cert_issuer: captured.cert_issuer,
                local_socket_ttl,
                ja3_fingerprint: captured.ja3_fingerprint,
                client_hello_capture: captured.client_hello_capture,
                connected_addr: opened.connected_addr,
                local_addr,
                cdn_provider: opened.cdn_provider,
                route_report,
            })
        }
        _ => build_plain_result(opened),
    }
}

struct OpenedTransportStream {
    stream: std::net::TcpStream,
    transport_open_ms: u64,
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
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> Result<OpenedTransportStream, ProbeStreamError> {
    let transport_start = Instant::now();
    let transport_result =
        connect_transport_observed_with_abort(targets, port, transport, should_abort).map_err(|err| {
            ProbeStreamError {
                stage: err.stage.into(),
                message: err.message,
                duration_ms: started.elapsed().as_millis().max(1) as u64,
                transport_open_ms: None,
                connected_addr: None,
                tls_server_hello_received: None,
                peer_response_after_client_hello: None,
                tls_alert: None,
                certificate_anomaly: false,
                tls_handshake_ms: None,
                client_hello: None,
            }
        })?;
    let transport_open_ms = transport_start.elapsed().as_millis() as u64;
    let connected_addr = transport_result.connected_addr;
    let cdn_provider = connected_addr.and_then(|addr| opportunistic_ech_provider_for_ip(addr.ip()).map(str::to_string));

    Ok(OpenedTransportStream {
        stream: transport_result.stream,
        transport_open_ms,
        connected_addr,
        local_addr: transport_result.local_addr,
        cdn_provider,
        route_report: transport_result.route_report,
    })
}

fn build_plain_result(opened: OpenedTransportStream) -> Result<ProbeStreamResult, ProbeStreamError> {
    let local_socket_ttl = platform_ttl::get_local_socket_ttl(&opened.stream);

    Ok(ProbeStreamResult {
        stream: ConnectionStream::Plain(opened.stream),
        negotiated_alpn: None,
        tls_template_first_flight_plan: None,
        transport_open_ms: opened.transport_open_ms,
        tls_handshake_ms: 0,
        cert_chain_length: None,
        cert_issuer: None,
        local_socket_ttl,
        ja3_fingerprint: None,
        client_hello_capture: ClientHelloCapture::NotSent,
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
        transport_open_ms: opened.map(|value| value.transport_open_ms),
        connected_addr: opened.and_then(|value| value.connected_addr),
        tls_server_hello_received: None,
        peer_response_after_client_hello: None,
        tls_alert: None,
        certificate_anomaly: false,
        tls_handshake_ms: None,
        client_hello: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};
    use std::time::Duration;

    #[test]
    fn transport_open_time_includes_socks_negotiation() {
        let proxy = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let endpoint = proxy.local_addr().unwrap();
        proxy.set_nonblocking(true).unwrap();
        let delay = Duration::from_millis(75);
        std::thread::scope(|scope| {
            let server = scope.spawn(|| {
                let deadline = Instant::now() + Duration::from_secs(2);
                let mut stream = loop {
                    match proxy.accept() {
                        Ok((stream, _)) => break stream,
                        Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(Instant::now() < deadline, "SOCKS connection did not start");
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(err) => panic!("SOCKS accept: {err}"),
                    }
                };
                stream.set_nonblocking(false).unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                let mut methods = [0; 3];
                stream.read_exact(&mut methods).unwrap();
                assert_eq!(methods, [5, 1, 0]);
                stream.write_all(&[5, 0]).unwrap();
                let mut request = [0; 10];
                stream.read_exact(&mut request).unwrap();
                assert_eq!(request, [5, 1, 0, 1, 127, 0, 0, 1, 1, 187]);
                std::thread::sleep(delay);
                stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 1, 187]).unwrap();
            });
            let result = open_probe_stream_targets_with_options(
                &[TargetAddress::Ip(Ipv4Addr::LOCALHOST.into())],
                443,
                &TransportConfig::Socks5 { host: endpoint.ip().to_string(), port: endpoint.port(), credentials: None },
                None,
                &ProbeStreamOptions::default(),
            )
            .unwrap();
            assert!(result.transport_open_ms >= delay.as_millis() as u64);
            assert!(matches!(result.stream, ConnectionStream::Plain(_)));
            server.join().unwrap();
        });
    }
}
