//! Verified HTTP/3 control request. QUIC Initial evidence remains a separate probe.
pub(crate) mod socket;
use bytes::Buf;
use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use ripdpi_diagnostics_contracts::{Http3Evidence, Http3ProbeConfig};
use ripdpi_diagnostics_transport::transport::{TransportConfig, start_address_resolution};
use rustls::client::danger::ServerCertVerifier;
use std::net::{IpAddr, SocketAddr};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

/// Run a bounded GET using certificate-verified TLS 1.3 and the h3 protocol.
/// The optional verifier is the existing internal test seam, never wire input.
pub fn probe_http3(
    config: &Http3ProbeConfig,
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Http3Evidence {
    let started = Instant::now();
    let mut evidence = Http3Evidence::default();
    let own_deadline = started + Duration::from_millis(config.timeout_ms.min(15000));
    // Capture the synchronous scan context before entering the Tokio runtime.
    let scan_deadline = active_scan_io_deadline();
    let deadline = scan_deadline.map_or(own_deadline, |value| value.min(own_deadline));
    if !raw_path || !matches!(transport, TransportConfig::Direct { .. }) {
        evidence.path_scope = "UNSUPPORTED_PROXY".into();
        fail(&mut evidence, "UNSUPPORTED", "unsupported_path");
    } else if config.validate().is_err() {
        fail(&mut evidence, "INVALID", "invalid_config");
    } else if cancel.load(Ordering::Acquire) {
        // Acquire observes the scan owner's published cancellation flag.
        fail(&mut evidence, "CANCELLED", "cancelled");
    } else if Instant::now() >= deadline {
        fail(&mut evidence, "TIMEOUT", "deadline_exceeded");
    } else {
        match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(runtime) => {
                let result = runtime.block_on(async {
                    tokio::select! {
                        biased;
                        () = cancelled(cancel) => Err(("CANCELLED", "cancelled")),
                        () = tokio::time::sleep_until(deadline.into()) => Err(("TIMEOUT", if scan_deadline.is_some_and(|d| d <= own_deadline) { "deadline_exceeded" } else { "timeout" })),
                        result = execute(config, verifier, started, deadline, &mut evidence) => result,
                    }
                });
                if let Err((status, reason)) = result {
                    fail(&mut evidence, status, reason);
                }
                // Runtime drop aborts all Quinn tasks after the request future and its
                // endpoint are dropped. There are no detached blocking tasks here.
            }
            Err(_) => fail(&mut evidence, "FAILED", "io_error"),
        }
    }
    evidence.duration_ms = Some(elapsed(started));
    evidence
}

type Failure = (&'static str, &'static str);
fn fail(evidence: &mut Http3Evidence, status: &str, reason: &str) {
    evidence.status = status.into();
    evidence.reason = Some(reason.into());
    if evidence.stage == "DNS" && status == "TIMEOUT" {
        evidence.dns_status = "TIMEOUT".into();
    }
}
fn elapsed(started: Instant) -> u64 {
    started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64
}

/// # Cancel safety:
/// cancel-safe: sleep is the only await. Drop removes the timer; the borrowed flag is unchanged.
// cancel-safe: timer and shared flag have no partially committed operation.
async fn cancelled(cancel: &AtomicBool) {
    loop {
        // Acquire observes the owner's cancellation publication.
        if cancel.load(Ordering::Acquire) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

/// # Cancel safety:
/// conditionally cancel-safe: cancelling DNS sleep drops its receiver while the
/// bounded resolver job continues. Cancelling attempt drops its owned resources
/// and keeps committed evidence. The caller drops the dedicated Quinn runtime.
// cancel-safe: no detached per-probe Tokio tasks or blocking Tokio worker jobs are created.
async fn execute(
    config: &Http3ProbeConfig,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    started: Instant,
    deadline: Instant,
    evidence: &mut Http3Evidence,
) -> Result<(), Failure> {
    let addresses = if let Some(ip) =
        config.connect_ip.as_ref().and_then(|s| s.parse::<IpAddr>().ok()).or_else(|| config.host.parse::<IpAddr>().ok())
    {
        evidence.dns_status = "PINNED".into();
        vec![SocketAddr::new(ip, config.port)]
    } else {
        let receiver =
            start_address_resolution(&config.host, config.port, deadline.saturating_duration_since(Instant::now()))
                .map_err(|_| {
                    evidence.dns_status = "FAILED".into();
                    ("FAILED", "dns_error")
                })?;
        loop {
            match receiver.try_recv() {
                Ok(Ok(values)) => {
                    evidence.dns_status = "RESOLVED".into();
                    break values;
                }
                Ok(Err(ripdpi_diagnostics_transport::transport::DnsResolveError::Timeout)) => {
                    evidence.dns_status = "TIMEOUT".into();
                    return Err(("TIMEOUT", "dns_timeout"));
                }
                Ok(Err(_)) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    evidence.dns_status = "FAILED".into();
                    return Err(("FAILED", "dns_error"));
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => tokio::time::sleep(Duration::from_millis(5)).await,
            }
        }
    };
    if addresses.is_empty() {
        return Err(("NOT_OBSERVED", "no_addresses"));
    }
    let mut last = Err(("NOT_OBSERVED", "no_addresses"));
    for address in addresses.into_iter().take(4) {
        // Evidence belongs to one peer; never combine failed-peer TLS with another peer's response.
        let attempts = evidence.attempt_count;
        let dns_status = evidence.dns_status.clone();
        *evidence = Http3Evidence { attempt_count: attempts, dns_status, ..Http3Evidence::default() };
        last = attempt(config, verifier, address, started, deadline, evidence).await;
        if last.is_ok() || evidence.http3_validated {
            break;
        }
    }
    last
}

fn client_config(verifier: Option<&Arc<dyn ServerCertVerifier>>) -> Result<quinn::ClientConfig, Failure> {
    let builder = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|_| ("FAILED", "tls_error"))?;
    let roots = rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut tls = if let Some(verifier) = verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        builder.with_root_certificates(roots).with_no_client_auth()
    };
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls).map_err(|_| ("FAILED", "tls_error"))?;
    let mut config = quinn::ClientConfig::new(Arc::new(crypto));
    let mut transport = quinn::TransportConfig::default();
    transport.max_concurrent_bidi_streams(0u32.into());
    transport.max_concurrent_uni_streams(8u32.into());
    transport.stream_receive_window(262144u32.into());
    transport.receive_window(1048576u32.into());
    config.transport_config(Arc::new(transport));
    Ok(config)
}

/// # Cancel safety:
/// conditionally cancel-safe: protection, Connecting, H3 build, send_request,
/// finish, recv_response, recv_data and recv_trailers waits discard their local
/// resources on cancellation. A partial GET may reach the peer; committed facts
/// remain. The caller must drop the dedicated runtime to abort Quinn tasks.
// cancel-safe: request progress is captured before waits and all I/O resources are owned locally.
async fn attempt(
    config: &Http3ProbeConfig,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    address: SocketAddr,
    started: Instant,
    deadline: Instant,
    evidence: &mut Http3Evidence,
) -> Result<(), Failure> {
    evidence.stage = "QUIC_HANDSHAKE".into();
    let socket = socket::protected_socket(address, deadline).await?;
    socket.set_nonblocking(true).map_err(|_| ("FAILED", "io_error"))?;
    let mut endpoint =
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))
            .map_err(|_| ("FAILED", "io_error"))?;
    endpoint.set_default_client_config(client_config(verifier)?);
    evidence.peer_address = Some(address.ip().to_string());
    let connecting = endpoint.connect(address, &config.host).map_err(|_| ("FAILED", "connect_error"))?;
    evidence.attempt_count += 1;
    let connection = connecting.await.map_err(connection_failure)?;
    evidence.tls_validated = true;
    evidence.handshake_elapsed_ms = Some(elapsed(started));
    let protocol = connection
        .handshake_data()
        .and_then(|data| data.downcast::<quinn::crypto::rustls::HandshakeData>().ok())
        .and_then(|data| data.protocol);
    if protocol.as_deref() != Some(b"h3") {
        return Err(("FAILED", "alpn_mismatch"));
    }
    evidence.alpn = Some("h3".into());
    let (mut driver, mut sender) = h3::client::builder()
        .max_field_section_size(16384)
        .build::<_, _, bytes::Bytes>(h3_quinn::Connection::new(connection.clone()))
        .await
        .map_err(|_| ("FAILED", "http3_error"))?;
    let request = async {
        evidence.stage = "HTTP_REQUEST".into();
        let host = if config.host.contains(':') { format!("[{}]", config.host) } else { config.host.clone() };
        let request = http::Request::builder()
            .method(http::Method::GET)
            .uri(format!("https://{host}:{}{}", config.port, config.path))
            .body(())
            .map_err(|_| ("INVALID", "invalid_config"))?;
        let mut stream = sender.send_request(request).await.map_err(|_| ("FAILED", "http3_error"))?;
        evidence.request_sent = true;
        stream.finish().await.map_err(|_| ("FAILED", "http3_error"))?;
        evidence.stage = "HTTP_HEADERS".into();
        let mut interim_count = 0;
        let response = loop {
            let response = stream.recv_response().await.map_err(|_| ("FAILED", "http3_error"))?;
            if !response.status().is_informational() {
                break response;
            }
            interim_count += 1;
            if response.status() == http::StatusCode::SWITCHING_PROTOCOLS || interim_count > 8 {
                return Err(("FAILED", "http3_error"));
            }
        };
        let status = response.status().as_u16();
        if !(200..=599).contains(&status) {
            return Err(("FAILED", "http3_error"));
        }
        evidence.http_status = Some(status);
        evidence.http3_validated = true;
        evidence.headers_elapsed_ms = Some(elapsed(started));
        evidence.stage = "HTTP_BODY".into();
        let expected_length = content_length(response.headers())?;
        while let Some(data) = stream.recv_data().await.map_err(|_| ("FAILED", "http3_error"))? {
            let size = data.remaining() as u64;
            if size > 0 && evidence.first_byte_elapsed_ms.is_none() {
                evidence.first_byte_elapsed_ms = Some(elapsed(started));
            }
            let remaining = config.max_response_bytes.saturating_sub(evidence.response_bytes);
            evidence.response_bytes += size.min(remaining);
            if size > remaining {
                return Err(("BODY_LIMIT", "body_limit"));
            }
        }
        // Trailers, if any, must also be drained so a body FIN is confirmed.
        stream.recv_trailers().await.map_err(|_| ("FAILED", "http3_error"))?;
        if expected_length.is_some_and(|length| length != evidence.response_bytes)
            || (status == 204 && (evidence.response_bytes != 0 || expected_length.is_some()))
        {
            return Err(("FAILED", "http3_error"));
        }
        evidence.body_complete = true;
        evidence.status = if (200..300).contains(&status) { "COMPLETE" } else { "HTTP_ERROR" }.into();
        evidence.reason = (!(200..300).contains(&status)).then(|| "http_status_error".into());
        Ok(())
    };
    let result = tokio::select! {
        biased;
        result = request => result,
        _ = std::future::poll_fn(|cx| driver.poll_close(cx)) => Err(("FAILED", "http3_error")),
    };
    connection.close(0u32.into(), b"probe complete");
    endpoint.close(0u32.into(), b"probe complete");
    result
}
fn content_length(headers: &http::HeaderMap) -> Result<Option<u64>, Failure> {
    let mut length = None;
    for value in headers.get_all(http::header::CONTENT_LENGTH) {
        for item in value.to_str().map_err(|_| ("FAILED", "http3_error"))?.split(',') {
            let text = item.trim();
            if text.is_empty() || !text.bytes().all(|byte| byte.is_ascii_digit()) {
                return Err(("FAILED", "http3_error"));
            }
            let parsed = text.parse::<u64>().map_err(|_| ("FAILED", "http3_error"))?;
            if length.is_some_and(|previous| previous != parsed) {
                return Err(("FAILED", "http3_error"));
            }
            length = Some(parsed);
        }
    }
    Ok(length)
}
fn connection_failure(error: quinn::ConnectionError) -> Failure {
    match error {
        quinn::ConnectionError::TimedOut => ("TIMEOUT", "timeout"),
        quinn::ConnectionError::TransportError(error) if error.code == quinn::TransportErrorCode::crypto(120) => {
            ("FAILED", "alpn_mismatch")
        }
        quinn::ConnectionError::TransportError(error)
            if u64::from(error.code) >= 0x100 && u64::from(error.code) <= 0x1ff =>
        {
            ("FAILED", "tls_error")
        }
        _ => ("FAILED", "connect_error"),
    }
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod socket_tests;
