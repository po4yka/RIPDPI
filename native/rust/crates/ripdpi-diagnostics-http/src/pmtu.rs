//! Authenticated QUIC DPLPMTUD observations, separated by address family.
use quinn::Runtime;
use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use ripdpi_diagnostics_contracts::{PmtuEvidence, PmtuProbeConfig};
use ripdpi_diagnostics_transport::transport::{TransportConfig, start_address_resolution};
use rustls::client::danger::ServerCertVerifier;
use std::net::{IpAddr, SocketAddr};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};
type Failure = (&'static str, &'static str);

/// Measure one address family. The verifier is an internal fixture seam, never wire input.
pub fn probe_pmtu(
    config: &PmtuProbeConfig,
    ipv6: bool,
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> PmtuEvidence {
    let started = Instant::now();
    let mut evidence = initial(config, ipv6);
    let own_deadline = started + Duration::from_millis(config.timeout_ms.min(15000));
    let deadline = active_scan_io_deadline().map_or(own_deadline, |d| d.min(own_deadline));
    let result = if !raw_path || !matches!(transport, TransportConfig::Direct { .. }) {
        evidence.path_scope = "UNSUPPORTED_PROXY".into();
        Err(("UNSUPPORTED", "unsupported_path"))
    } else if config.validate().is_err() {
        Err(("INVALID", "invalid_config"))
    } else if cancel.load(Ordering::Acquire) {
        // Acquire observes cancellation published by the scan owner.
        Err(("CANCELLED", "cancelled"))
    } else if Instant::now() >= deadline {
        Err(("TIMEOUT", "deadline_exceeded"))
    } else {
        match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(runtime) => runtime.block_on(async {
                tokio::select! { biased;
                    () = cancelled(cancel) => Err(("CANCELLED", "cancelled")),
                    () = tokio::time::sleep_until(deadline.into()) => Err(("TIMEOUT", "deadline_exceeded")),
                    result = execute(config, ipv6, verifier, started, deadline, &mut evidence) => result,
                }
            }),
            Err(_) => Err(("FAILED", "io_error")),
        }
    };
    if let Err((status, reason)) = result {
        evidence.status = status.into();
        evidence.reason = Some(reason.into());
    }
    evidence.duration_ms = Some(elapsed(started));
    evidence
}
fn initial(config: &PmtuProbeConfig, ipv6: bool) -> PmtuEvidence {
    PmtuEvidence {
        address_family: if ipv6 { "IPV6" } else { "IPV4" }.into(),
        configured_upper_bound_udp_payload_bytes: config.upper_bound_udp_payload_bytes,
        ..Default::default()
    }
}
fn elapsed(started: Instant) -> u64 {
    started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64
}
/// # Cancel safety:
/// cancel-safe: timer cancellation drops the timer without changing the borrowed flag.
// cancel-safe: only timer awaits and a shared cancellation read.
async fn cancelled(cancel: &AtomicBool) {
    loop {
        // Acquire observes cancellation published by the scan owner.
        if cancel.load(Ordering::Acquire) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}
/// # Cancel safety:
/// conditionally cancel-safe: DNS wait drops its receiver; the fixed resolver pool
/// owns pending lookup work. Socket/connection waits discard local resources and
/// retain already committed facts. The caller drops the dedicated Quinn runtime.
// cancel-safe: no per-probe blocking threads; runtime owns every Quinn task.
async fn execute(
    config: &PmtuProbeConfig,
    ipv6: bool,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    started: Instant,
    deadline: Instant,
    evidence: &mut PmtuEvidence,
) -> Result<(), Failure> {
    let pin = if ipv6 { &config.connect_ipv6 } else { &config.connect_ipv4 };
    let addresses = if let Some(ip) =
        pin.as_ref().and_then(|s| s.parse::<IpAddr>().ok()).or_else(|| config.host.parse::<IpAddr>().ok())
    {
        vec![SocketAddr::new(ip, config.port)]
    } else {
        let receiver =
            start_address_resolution(&config.host, config.port, deadline.saturating_duration_since(Instant::now()))
                .map_err(|_| ("INCONCLUSIVE", "dns_error"))?;
        loop {
            match receiver.try_recv() {
                Ok(Ok(values)) => break values,
                Ok(Err(ripdpi_diagnostics_transport::transport::DnsResolveError::Timeout)) => {
                    return Err(("TIMEOUT", "dns_timeout"));
                }
                Ok(Err(_)) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    return Err(("INCONCLUSIVE", "dns_error"));
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => tokio::time::sleep(Duration::from_millis(5)).await,
            }
        }
    };
    let address = addresses
        .into_iter()
        .find(|a| a.is_ipv6() == ipv6 && !matches!(a.ip(), IpAddr::V6(ip) if ip.to_ipv4_mapped().is_some()))
        .ok_or(("INCONCLUSIVE", "no_family_address"))?;
    let socket = crate::http3::socket::protected_socket(address, deadline).await?;
    socket.set_nonblocking(true).map_err(|_| ("FAILED", "io_error"))?;
    let socket = quinn::TokioRuntime.wrap_udp_socket(socket).map_err(|_| ("FAILED", "io_error"))?;
    observe(config, verifier, address, socket, started, evidence).await
}
fn client_config(
    config: &PmtuProbeConfig,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<quinn::ClientConfig, Failure> {
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
    let mut client = quinn::ClientConfig::new(Arc::new(crypto));
    let mut transport = quinn::TransportConfig::default();
    let mut discovery = quinn::MtuDiscoveryConfig::default();
    discovery.upper_bound(config.upper_bound_udp_payload_bytes);
    transport.initial_mtu(1200).min_mtu(1200).mtu_discovery_config(Some(discovery));
    transport.keep_alive_interval(Some(Duration::from_millis(100)));
    transport.max_concurrent_bidi_streams(0u32.into());
    transport.max_concurrent_uni_streams(8u32.into());
    transport.stream_receive_window(16384u32.into());
    transport.receive_window(65536u32.into());
    client.transport_config(Arc::new(transport));
    Ok(client)
}
/// # Cancel safety:
/// conditionally cancel-safe: handshake, H3 setup/driver and timer awaits own local endpoint and
/// connection resources. Drop retains last sampled evidence; the caller must drop
/// its dedicated runtime to abort all Quinn driver tasks. Partial H3 control frames
/// can reach the peer; no HTTP request or application payload is sent.
// cancel-safe: all network resources are confined to the dedicated probe runtime.
async fn observe(
    config: &PmtuProbeConfig,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    address: SocketAddr,
    socket: Arc<dyn quinn::AsyncUdpSocket>,
    started: Instant,
    evidence: &mut PmtuEvidence,
) -> Result<(), Failure> {
    if socket.may_fragment() {
        return Err(("UNSUPPORTED", "fragmentation_unsupported"));
    }
    evidence.fragmentation_prevented = true;
    let mut endpoint = quinn::Endpoint::new_with_abstract_socket(
        quinn::EndpointConfig::default(),
        None,
        socket,
        Arc::new(quinn::TokioRuntime),
    )
    .map_err(|_| ("FAILED", "io_error"))?;
    endpoint.set_default_client_config(client_config(config, verifier)?);
    evidence.peer_address = Some(address.ip().to_string());
    let connection = endpoint
        .connect(address, &config.host)
        .map_err(|_| ("INCONCLUSIVE", "quic_error"))?
        .await
        .map_err(connection_failure)?;
    let protocol = connection
        .handshake_data()
        .and_then(|data| data.downcast::<quinn::crypto::rustls::HandshakeData>().ok())
        .and_then(|data| data.protocol);
    if protocol.as_deref() != Some(b"h3") {
        return Err(("INCONCLUSIVE", "alpn_mismatch"));
    }
    evidence.tls_validated = true;
    evidence.handshake_elapsed_ms = Some(elapsed(started));
    evidence.alpn = Some("h3".into());
    let mut observed = Observation { connection, evidence };
    // h3 ALPN requires the control streams and SETTINGS even without a request.
    let (mut driver, _sender) = h3::client::builder()
        .max_field_section_size(16384)
        .build::<_, _, bytes::Bytes>(h3_quinn::Connection::new(observed.connection.clone()))
        .await
        .map_err(|_| ("INCONCLUSIVE", "quic_error"))?;
    let measurement = async {
        let until = Instant::now() + Duration::from_millis(config.observation_ms);
        loop {
            observed.capture();
            if observed.connection.close_reason().is_some() {
                return Err(("INCONCLUSIVE", "connection_closed"));
            }
            if Instant::now() >= until {
                break;
            }
            tokio::time::sleep_until((Instant::now() + Duration::from_millis(10)).min(until).into()).await;
        }
        observed.evidence.observation_window_complete = true;
        observed.evidence.status = if observed.evidence.acknowledged_udp_payload_lower_bound_bytes.is_some() {
            "OBSERVED"
        } else {
            "INCONCLUSIVE"
        }
        .into();
        observed.evidence.reason = observed
            .evidence
            .acknowledged_udp_payload_lower_bound_bytes
            .is_none()
            .then(|| "no_acknowledged_probe".into());
        Ok(())
    };
    let result = tokio::select! { biased;
        result = measurement => result,
        _ = std::future::poll_fn(|cx| driver.poll_close(cx)) => Err(("INCONCLUSIVE", "connection_closed")),
    };
    observed.connection.close(0u32.into(), b"probe complete");
    endpoint.close(0u32.into(), b"probe complete");
    result
}
// Capture final counters even when the outer select cancels the observation timer.
// This private short-lived guard borrows only within observe; it does not enter a public API.
struct Observation<'a> {
    connection: quinn::Connection,
    evidence: &'a mut PmtuEvidence,
}
impl Observation<'_> {
    fn capture(&mut self) {
        record(&self.connection, self.evidence);
    }
}
impl Drop for Observation<'_> {
    fn drop(&mut self) {
        self.capture();
    }
}

fn record(connection: &quinn::Connection, evidence: &mut PmtuEvidence) {
    let path = connection.stats().path;
    evidence.current_udp_payload_bytes = Some(path.current_mtu);
    // With initial=min=1200 and no migration, only an ACKed discovery probe can
    // raise current_mtu. The baseline itself is never treated as a measurement.
    evidence.acknowledged_udp_payload_lower_bound_bytes = (path.current_mtu > 1200).then_some(path.current_mtu);
    evidence.configured_upper_bound_reached = path.current_mtu >= evidence.configured_upper_bound_udp_payload_bytes;
    evidence.sent_probe_count = path.sent_plpmtud_probes;
    evidence.lost_probe_count = path.lost_plpmtud_probes;
    evidence.black_hole_count = path.black_holes_detected;
}
fn connection_failure(error: quinn::ConnectionError) -> Failure {
    match error {
        quinn::ConnectionError::TimedOut => ("TIMEOUT", "timeout"),
        quinn::ConnectionError::TransportError(error) if error.code == quinn::TransportErrorCode::crypto(120) => {
            ("INCONCLUSIVE", "alpn_mismatch")
        }
        quinn::ConnectionError::TransportError(error) if (0x100..=0x1ff).contains(&u64::from(error.code)) => {
            ("INCONCLUSIVE", "tls_error")
        }
        _ => ("INCONCLUSIVE", "quic_error"),
    }
}
#[cfg(test)]
mod tests;

#[cfg(test)]
#[path = "pmtu/socket_tests.rs"]
mod socket_tests;
