use std::borrow::Cow;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};

use ripdpi_tls_profiles::{TlsTemplateFirstFlightPlan, plan_first_flight};
use rustls::client::EchStatus;
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, StreamOwned};

use crate::ja3::{self, RecordingStream};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

use super::super::certs::extract_cert_info;
use super::super::config::{
    EchLookupFailure, build_ech_client_config_from_list, build_ech_comparison_control_config,
    build_standard_client_config_with_key_log, make_server_name, planned_tls_template_profile, resolve_ech_config,
};
use super::super::key_log::TlsKeyLogCallback;
use super::super::types::{
    ApplicationProtocolPolicy, ClientHelloCapture, ClientHelloEvidence, EchConfigSource, TlsClientProfile,
};

pub(crate) struct CapturedTlsHandshake {
    pub(crate) stream: ConnectionStream,
    pub(crate) tls_template_first_flight_plan: Option<TlsTemplateFirstFlightPlan>,
    pub(super) ech_config_source: Option<EchConfigSource>,
    pub(crate) tls_handshake_ms: u64,
    pub(crate) negotiated_alpn: Option<String>,
    pub(crate) cert_chain_length: Option<usize>,
    pub(crate) cert_issuer: Option<String>,
    pub(crate) ja3_fingerprint: Option<String>,
    pub(super) client_hello_capture: ClientHelloCapture,
}

/// A failed handshake and whether the server's ServerHello was processed before it failed.
pub(crate) struct CaptureTlsError {
    pub(crate) message: String,
    pub(crate) server_hello_received: Option<bool>,
    pub(super) peer_response_after_client_hello: Option<bool>,
    pub(crate) alert: Option<rustls::AlertDescription>,
    pub(crate) certificate_anomaly: bool,
    pub(crate) handshake_ms: Option<u64>,
    pub(super) client_hello: Option<Box<ClientHelloEvidence>>,
    pub(super) ech_config_source: Option<EchConfigSource>,
    pub(super) ech_status: Option<EchStatus>,
    pub(super) ech_rejected: bool,
    pub(super) ech_lookup_failure: Option<Box<EchLookupFailure>>,
    pub(super) ech_retry_skipped_reason: Option<&'static str>,
    pub(crate) ech_retry_configs: Option<Box<[u8]>>,
}

impl From<String> for CaptureTlsError {
    fn from(message: String) -> Self {
        Self {
            message,
            server_hello_received: None,
            peer_response_after_client_hello: None,
            alert: None,
            certificate_anomaly: false,
            handshake_ms: None,
            client_hello: None,
            ech_config_source: None,
            ech_status: None,
            ech_rejected: false,
            ech_lookup_failure: None,
            ech_retry_skipped_reason: None,
            ech_retry_configs: None,
        }
    }
}

pub(crate) struct CaptureTlsHandshakeOptions<'a> {
    pub(crate) targets: &'a [TargetAddress],
    pub(crate) port: u16,
    pub(crate) ech_retry_configs: Option<&'a [u8]>,
    pub(crate) transport: &'a TransportConfig,
    pub(crate) tls_name: &'a str,
    pub(crate) profile: TlsClientProfile,
    pub(crate) tls_verifier: Option<&'a Arc<dyn ServerCertVerifier>>,
    #[cfg(test)]
    pub(crate) fixture_ech_roots: Option<&'a rustls::RootCertStore>,
    #[cfg(test)]
    pub(crate) fixture_ech_lookup: Option<&'a ripdpi_diagnostics_dns::dns::EchResolutionOutcome>,
    pub(crate) key_log: Option<&'a TlsKeyLogCallback>,
    pub(crate) application_protocol: ApplicationProtocolPolicy,
    pub(crate) should_abort: &'a dyn Fn() -> Option<&'static str>,
}

fn comparison_ech_query_name(server_name: &str, port: u16) -> Cow<'_, str> {
    // RFC 9460 section 9.1 prefixes HTTPS query names for non-default ports.
    if port == 443 { Cow::Borrowed(server_name) } else { Cow::Owned(format!("_{port}._https.{server_name}")) }
}

pub(crate) fn capture_tls_handshake(
    socket: TcpStream,
    connected_addr: Option<SocketAddr>,
    options: &CaptureTlsHandshakeOptions<'_>,
) -> Result<CapturedTlsHandshake, CaptureTlsError> {
    let should_abort = options.should_abort;
    if let Some(reason) = should_abort() {
        return Err(format!("probe_aborted:{reason}").into());
    }
    let target = options.targets.first().ok_or_else(|| "no_tls_targets".to_string())?;
    let template_profile = planned_tls_template_profile(options.profile);
    let server_name = make_server_name(options.tls_name, target)?;
    let (config, ech_config_source) = if options.profile.uses_real_ech() {
        let (config, source) = capture_real_ech_config(&server_name, connected_addr, options)?;
        (config, Some(source))
    } else if options.profile.is_comparison() {
        let config = build_ech_comparison_control_config(
            options.profile,
            options.tls_verifier,
            options.key_log,
            options.application_protocol,
            #[cfg(test)]
            options.fixture_ech_roots,
        )?;
        (config, None)
    } else {
        (
            build_standard_client_config_with_key_log(
                options.profile,
                options.tls_verifier,
                options.key_log,
                options.application_protocol,
            ),
            None,
        )
    };
    let mut connection = ClientConnection::new(config, server_name).map_err(|error| {
        if matches!(options.profile, TlsClientProfile::ComparisonEch) && matches!(error, rustls::Error::Other(_)) {
            // The provider can reject the published key when preparing HPKE for the first ClientHello.
            ech_setup_error(EchLookupFailure::InvalidConfig(error.to_string()), ech_config_source)
        } else {
            CaptureTlsError { ech_config_source, ..error.to_string().into() }
        }
    })?;
    let mut recording = RecordingStream::new(socket);

    let tls_start = Instant::now();
    while connection.is_handshaking() {
        let mut io = BoundedHandshakeIo { recording: &mut recording, should_abort };
        if let Err(err) = connection.complete_io(&mut io) {
            let tls_error = err.get_ref().and_then(|error| error.downcast_ref::<rustls::Error>());
            let message = if tls_error.is_some() {
                err.to_string()
            } else {
                administrative_error_after_io(should_abort).unwrap_or_else(|| err.to_string())
            };
            let version = connection.protocol_version();
            // rustls sets the version and cipher suite while processing HelloRetryRequest too.
            let server_hello_received = match version {
                Some(rustls::ProtocolVersion::TLSv1_3) => connection.negotiated_key_exchange_group().is_some(),
                _ => connection.negotiated_cipher_suite().is_some(),
            };
            let handshake_ms = Some(tls_start.elapsed().as_millis() as u64);
            let client_hello = capture_client_hello(recording.recorded_writes());
            let alert = match tls_error {
                Some(rustls::Error::AlertReceived(alert)) => Some(*alert),
                _ => None,
            };
            let certificate_anomaly = matches!(tls_error, Some(rustls::Error::InvalidCertificate(_)));
            let peer_replied = version.is_some() || alert.is_some();
            let peer_response_after_client_hello =
                (peer_replied || client_hello.capture == ClientHelloCapture::Complete).then_some(peer_replied);
            let ech_retry_configs = tls_error
                .filter(|_| options.tls_verifier.is_none())
                .and_then(ripdpi_tls_profiles::ech::retry::rustls_ech_retry_configs);
            let ech_rejection = match tls_error {
                Some(rustls::Error::PeerIncompatible(
                    rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(configs),
                )) => Some(configs),
                _ => None,
            };
            let ech_retry_skipped_reason = (options.tls_verifier.is_some()
                && ech_rejection.is_some_and(Option::is_some))
            .then_some("certificate_policy_override");
            let ech_status = ech_config_source
                .filter(|_| client_hello.capture == ClientHelloCapture::Complete)
                .map(|_| connection.ech_status());
            return Err(CaptureTlsError {
                message,
                server_hello_received: Some(server_hello_received),
                peer_response_after_client_hello,
                alert,
                certificate_anomaly,
                handshake_ms,
                client_hello: Some(Box::new(client_hello)),
                ech_config_source,
                ech_status,
                ech_rejected: ech_rejection.is_some(),
                ech_lookup_failure: None,
                ech_retry_skipped_reason,
                ech_retry_configs: ech_retry_configs.map(Vec::into_boxed_slice),
            });
        }
    }
    let client_hello = capture_client_hello(recording.recorded_writes());
    let tls_handshake_ms = tls_start.elapsed().as_millis() as u64;
    let comparison_rejected =
        matches!(options.profile, TlsClientProfile::ComparisonEch) && connection.ech_status() != EchStatus::Accepted;
    let setup_error =
        administrative_error_after_io(should_abort).or_else(|| refresh_tls_handshake_timeout(&mut recording).err());
    if setup_error.is_some() || comparison_rejected {
        let ech_rejected = setup_error.is_none() && connection.ech_status() == EchStatus::Rejected;
        let message = setup_error.unwrap_or_else(|| "ech_not_accepted".to_owned());
        return Err(CaptureTlsError {
            server_hello_received: Some(true),
            peer_response_after_client_hello: Some(true),
            handshake_ms: Some(tls_handshake_ms),
            client_hello: Some(Box::new(client_hello)),
            ech_config_source,
            ech_status: ech_config_source.map(|_| connection.ech_status()),
            ech_rejected,
            ..message.into()
        });
    }

    let tls_template_first_flight_plan = if options.profile.is_comparison() {
        None
    } else {
        first_tls_record(recording.recorded_writes()).and_then(|hello| plan_first_flight(template_profile, hello))
    };
    let (socket, _recorded) = recording.into_parts();
    let tls_stream = StreamOwned::new(connection, socket);
    let negotiated_alpn =
        tls_stream.conn.alpn_protocol().map(|protocol| String::from_utf8_lossy(protocol).into_owned());
    let (cert_chain_length, cert_issuer) = extract_cert_info(&tls_stream.conn);

    Ok(CapturedTlsHandshake {
        stream: ConnectionStream::Tls(Box::new(tls_stream)),
        tls_template_first_flight_plan,
        ech_config_source,
        tls_handshake_ms,
        negotiated_alpn,
        cert_chain_length,
        cert_issuer,
        ja3_fingerprint: client_hello.ja3_fingerprint,
        client_hello_capture: client_hello.capture,
    })
}

fn capture_real_ech_config(
    server_name: &ServerName<'_>,
    connected_addr: Option<SocketAddr>,
    options: &CaptureTlsHandshakeOptions<'_>,
) -> Result<(Arc<ClientConfig>, EchConfigSource), CaptureTlsError> {
    let should_abort = options.should_abort;
    let (bytes, source) = if let Some(configs) = options.ech_retry_configs {
        (configs.to_vec(), EchConfigSource::RetryConfigs)
    } else {
        // A comparison needs the config published for this origin. The peer-IP fallback is legacy-only.
        let fallback_peer = if options.profile.is_comparison() { None } else { connected_addr };
        let origin_name = server_name.to_str();
        let query_name = if options.profile.is_comparison() {
            comparison_ech_query_name(&origin_name, options.port)
        } else {
            Cow::Borrowed(origin_name.as_ref())
        };
        let resolved = resolve_ech_config(
            &query_name,
            options.profile.is_comparison().then_some(options.port),
            fallback_peer,
            options.transport,
            should_abort,
            #[cfg(test)]
            options.fixture_ech_lookup,
        );
        if let Some(reason) = should_abort() {
            return Err(format!("probe_aborted:{reason}").into());
        }
        resolved.map_err(|failure| ech_setup_error(failure, None))?
    };
    let config = build_ech_client_config_from_list(
        bytes,
        options.tls_verifier,
        options.key_log,
        options.application_protocol,
        #[cfg(test)]
        options.fixture_ech_roots,
    )
    .map_err(|error| ech_setup_error(EchLookupFailure::InvalidConfig(error), Some(source)))?;
    Ok((config, source))
}

fn ech_setup_error(failure: EchLookupFailure, ech_config_source: Option<EchConfigSource>) -> CaptureTlsError {
    let message = failure.to_string();
    CaptureTlsError { ech_config_source, ech_lookup_failure: Some(Box::new(failure)), ..message.into() }
}

fn capture_client_hello(recorded: &[u8]) -> ClientHelloEvidence {
    let ja3_fingerprint = ja3::compute_ja3(recorded);
    let capture = if ja3_fingerprint.is_some() {
        ClientHelloCapture::Complete
    } else if recorded.is_empty() {
        ClientHelloCapture::NotSent
    } else {
        ClientHelloCapture::IncompleteOrInvalid
    };
    ClientHelloEvidence { ja3_fingerprint, capture }
}

fn first_tls_record(recorded: &[u8]) -> Option<&[u8]> {
    let header = recorded.get(..5)?;
    let payload_len = usize::from(u16::from_be_bytes([header[3], header[4]]));
    recorded.get(..5 + payload_len)
}

fn administrative_error_after_io(should_abort: &dyn Fn() -> Option<&'static str>) -> Option<String> {
    if let Some(reason) = should_abort() {
        Some(format!("probe_aborted:{reason}"))
    } else if bounded_scan_io_timeout(IO_TIMEOUT).is_err() {
        Some("scan_deadline_exceeded".to_string())
    } else {
        None
    }
}

/// Handshake socket I/O that checks cancellation and the scan deadline before every read and
/// write. `complete_io` loops until the handshake ends, and a socket timeout only bounds one
/// read, so a peer that sends a few bytes within every timeout would otherwise hold the probe
/// past the deadline and past a user cancel.
struct BoundedHandshakeIo<'a> {
    recording: &'a mut RecordingStream<TcpStream>,
    should_abort: &'a dyn Fn() -> Option<&'static str>,
}

impl BoundedHandshakeIo<'_> {
    fn poll_io<T>(
        &mut self,
        set_timeout: impl Fn(&TcpStream, Option<Duration>) -> io::Result<()>,
        mut operation: impl FnMut(&mut RecordingStream<TcpStream>) -> io::Result<T>,
    ) -> io::Result<T> {
        // A short socket wait observes cancellation on a silent peer. Retry the same
        // read or write up to its original idle budget, also limited by the scan deadline.
        const CANCEL_POLL: Duration = Duration::from_millis(50);
        let timeout =
            bounded_scan_io_timeout(IO_TIMEOUT).map_err(|err| io::Error::new(io::ErrorKind::TimedOut, err))?;
        let deadline = Instant::now() + timeout;
        loop {
            // Avoid Interrupted because rustls retries it internally.
            if let Some(reason) = (self.should_abort)() {
                return Err(io::Error::other(format!("probe_aborted:{reason}")));
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(io::Error::from(io::ErrorKind::TimedOut));
            }
            set_timeout(self.recording.inner_mut(), Some(remaining.min(CANCEL_POLL)))?;
            match operation(self.recording) {
                Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                result => return result,
            }
        }
    }
}

impl Read for BoundedHandshakeIo<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.poll_io(TcpStream::set_read_timeout, |recording| recording.read(buf))
    }
}

impl Write for BoundedHandshakeIo<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.poll_io(TcpStream::set_write_timeout, |recording| recording.write(buf))
    }

    fn flush(&mut self) -> io::Result<()> {
        self.recording.flush()
    }
}

fn refresh_tls_handshake_timeout(recording: &mut RecordingStream<TcpStream>) -> Result<(), String> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    let socket = recording.inner_mut();
    socket.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;
    use rustls::client::danger::ServerCertVerifier;
    use rustls::{RootCertStore, ServerConfig, ServerConnection};

    use crate::tls::{ClientHelloCapture, TlsClientProfile, open_probe_stream, try_tls_handshake};
    use crate::transport::{ConnectionStream, TargetAddress, direct_transport};

    #[test]
    fn comparison_ech_query_names_use_origin_port() {
        use ripdpi_diagnostics_dns::dns::build_dns_query_with_type;

        let default_port = super::comparison_ech_query_name("inner.example", 443);
        assert_eq!(default_port, "inner.example");
        let other_port = super::comparison_ech_query_name("inner.example", 8443);
        assert_eq!(other_port, "_8443._https.inner.example");

        let packet = build_dns_query_with_type(&other_port, 1, 65).unwrap();
        assert_eq!(&packet[12..], b"\x05_8443\x06_https\x05inner\x07example\0\0\x41\0\x01");
    }

    #[test]
    fn successful_handshakes_keep_the_first_client_hello_plan() {
        for (profile, version, alpn) in [
            (TlsClientProfile::Auto, rustls::ProtocolVersion::TLSv1_3, Some("h2")),
            (TlsClientProfile::Tls12Only, rustls::ProtocolVersion::TLSv1_2, Some("http/1.1")),
            (TlsClientProfile::Tls13Only, rustls::ProtocolVersion::TLSv1_3, None),
        ] {
            let certificate = rcgen::generate_simple_self_signed(vec!["localhost".to_owned()]).unwrap();
            let provider = Arc::new(rustls::crypto::ring::default_provider());
            let mut roots = RootCertStore::empty();
            roots.add(certificate.cert.der().clone()).unwrap();
            let verifier: Arc<dyn ServerCertVerifier> =
                rustls::client::WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider.clone())
                    .build()
                    .unwrap();
            let mut server_config = ServerConfig::builder_with_provider(provider)
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(
                    vec![certificate.cert.der().clone()],
                    rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into(),
                )
                .unwrap();
            server_config.alpn_protocols = alpn.map(|value| value.as_bytes().to_vec()).into_iter().collect();
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            let port = listener.local_addr().unwrap().port();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
                socket.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
                let mut connection = ServerConnection::new(Arc::new(server_config)).unwrap();
                while connection.is_handshaking() {
                    connection.complete_io(&mut socket).unwrap();
                }
            });

            let result = open_probe_stream(
                &TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST)),
                port,
                &direct_transport(),
                Some("localhost"),
                profile,
                Some(&verifier),
            )
            .unwrap();
            let ConnectionStream::Tls(stream) = &result.stream else {
                panic!("expected a TLS stream for {profile:?}");
            };
            assert_eq!(stream.conn.protocol_version(), Some(version), "{profile:?}");
            assert!(result.ja3_fingerprint.is_some(), "{profile:?}");
            server.join().unwrap();
            assert!(result.tls_template_first_flight_plan.is_some(), "{profile:?}: first-flight plan missing");
            let observation = crate::tls::probe::observation::from_probe_stream_result(result, profile);
            assert_eq!(observation.negotiated_alpn.as_deref(), alpn);
        }
    }

    #[test]
    fn first_record_extraction_rejects_incomplete_records_and_excludes_later_writes() {
        let records = [0x16, 0x03, 0x03, 0x00, 0x02, 0x01, 0x00, 0x14, 0x03, 0x03, 0x00, 0x01, 0x01];
        for length in 0..7 {
            assert!(super::first_tls_record(&records[..length]).is_none(), "prefix length {length}");
        }
        assert_eq!(super::first_tls_record(&records), Some(&records[..7]));
        assert!(super::first_tls_record(&[0x16, 0x03, 0x03, 0xff, 0xff]).is_none());
    }

    /// Accepts one client, reads its ClientHello, then sends a TLS record header and one byte
    /// of the record every 100 ms for `drip_for`: each read returns well within the socket
    /// timeout, but the handshake never completes.
    fn slow_drip_server(drip_for: Duration) -> (u16, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut hello = [0u8; 4096];
            let _ = socket.read(&mut hello);
            if socket.write_all(&[0x16, 0x03, 0x03, 0x40, 0x00]).is_err() {
                return;
            }
            let started = Instant::now();
            while started.elapsed() < drip_for {
                std::thread::sleep(Duration::from_millis(100));
                if socket.write_all(&[0x00]).is_err() {
                    return;
                }
            }
        });
        (port, server)
    }

    /// Answers the ClientHello with `reply(client_hello)` and closes.
    fn scripted_server(reply: impl FnOnce(&[u8]) -> Vec<u8> + Send + 'static) -> (u16, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut header = [0u8; 5];
            socket.read_exact(&mut header).unwrap();
            let payload_len = u16::from_be_bytes([header[3], header[4]]);
            let mut hello = header.to_vec();
            hello.resize(5 + usize::from(payload_len), 0);
            socket.read_exact(&mut hello[5..]).unwrap();
            let _ = socket.write_all(&reply(&hello));
            let _ = socket.read(&mut [0u8; 64]);
        });
        (port, server)
    }

    const HANDSHAKE_FAILURE_ALERT: [u8; 7] = [0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];

    /// A TLS 1.2 ServerHello (RFC 5246 section 7.4.1.3) for the first ECDHE cipher suite the
    /// client offered, followed by a handshake_failure alert instead of the Certificate message.
    fn server_hello_then_alert(client_hello: &[u8]) -> Vec<u8> {
        // Record header (5) + handshake header (4) + version (2) + random (32).
        let session_id_len = usize::from(client_hello[43]);
        let suites_len_at = 44 + session_id_len;
        let suites_len =
            usize::from(u16::from_be_bytes([client_hello[suites_len_at], client_hello[suites_len_at + 1]]));
        let suite = client_hello[suites_len_at + 2..suites_len_at + 2 + suites_len]
            .as_chunks::<2>()
            .0
            .iter()
            .find(|suite| suite[0] == 0xc0 || **suite == [0xcc, 0xa8] || **suite == [0xcc, 0xa9])
            .expect("the TLS 1.2 client offers an ECDHE suite")
            .to_vec();
        let mut body = vec![0x03, 0x03];
        body.extend_from_slice(&[0x42; 32]);
        body.push(0x00);
        body.extend_from_slice(&suite);
        body.push(0x00);
        let mut handshake = vec![0x02, 0x00, 0x00, body.len() as u8];
        handshake.extend_from_slice(&body);
        let mut reply = vec![0x16, 0x03, 0x03, 0x00, handshake.len() as u8];
        reply.extend_from_slice(&handshake);
        reply.extend_from_slice(&HANDSHAKE_FAILURE_ALERT);
        reply
    }

    fn probe_tls12(port: u16) -> crate::tls::TlsObservation {
        try_tls_handshake(
            &TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST)),
            port,
            &direct_transport(),
            "localhost",
            TlsClientProfile::Tls12Only,
            None,
        )
    }

    #[test]
    fn handshake_observation_distinguishes_wire_alert_from_server_hello() {
        let (port, server) = scripted_server(server_hello_then_alert);
        let after_server_hello = probe_tls12(port);
        server.join().unwrap();
        let (port, server) = scripted_server(|_| Vec::new());
        let silent_close = probe_tls12(port);
        server.join().unwrap();

        assert_eq!(after_server_hello.tls_alert_code, Some(0x28), "{after_server_hello:?}");
        assert_eq!(after_server_hello.tls_server_hello_received, Some(true));
        assert_eq!(silent_close.failure_stage, Some(crate::tls::ProbeStreamFailureStage::TlsHandshake));
        assert_eq!(silent_close.tls_alert_code, None, "{silent_close:?}");
        assert_eq!(silent_close.tls_server_hello_received, Some(false));

        for (code, description) in [
            (0x28, Some("HandshakeFailure")),
            (0x46, Some("ProtocolVersion")),
            (0x70, Some("UnrecognisedName")),
            (0x74, Some("CertificateRequired")),
            (0xfa, None),
        ] {
            let (port, server) = scripted_server(move |_| {
                let mut alert = HANDSHAKE_FAILURE_ALERT;
                alert[6] = code;
                alert.to_vec()
            });
            let observation = probe_tls12(port);
            server.join().unwrap();

            assert_eq!(observation.tls_alert_code, Some(code), "{observation:?}");
            assert_eq!(observation.client_hello_capture, ClientHelloCapture::Complete);
            assert!(observation.ja3_fingerprint.is_some());
            assert_eq!(observation.tls_alert_description.as_deref(), description);
            assert_eq!(observation.tls_server_hello_received, Some(false));
            assert_eq!(observation.tls_peer_response_after_client_hello, Some(true));
            assert_eq!(observation.status, "tls_handshake_failed");
            assert!(!observation.certificate_anomaly);
            assert!(observation.tls_handshake_ms.is_some());
        }
    }

    #[test]
    fn hello_retry_request_is_a_response_without_a_server_hello() {
        let (port, server) = scripted_server(|hello| {
            let certificate = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
            let key = rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der());
            let mut provider = rustls::crypto::ring::default_provider();
            provider.kx_groups = vec![rustls::crypto::ring::kx_group::SECP256R1];
            let config = ServerConfig::builder_with_provider(Arc::new(provider))
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(vec![certificate.cert.der().clone()], key.into())
                .unwrap();
            let mut server = ServerConnection::new(Arc::new(config)).unwrap();
            let mut client_hello = hello;
            server.read_tls(&mut client_hello).unwrap();
            server.process_new_packets().unwrap();
            let mut response = Vec::new();
            server.write_tls(&mut response).unwrap();
            response.extend_from_slice(&HANDSHAKE_FAILURE_ALERT);
            response
        });
        let observation = try_tls_handshake(
            &TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST)),
            port,
            &direct_transport(),
            "localhost",
            TlsClientProfile::Tls13Only,
            None,
        );
        server.join().unwrap();

        assert_eq!(observation.tls_alert_code, Some(0x28));
        assert_eq!(observation.tls_server_hello_received, Some(false));
        assert_eq!(observation.tls_peer_response_after_client_hello, Some(true));
    }

    #[test]
    fn failure_before_tls_has_no_server_hello_flag() {
        // Bind then drop a listener: the port refuses the TCP connect.
        let port = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap().port();

        let observation = probe_tls12(port);

        assert_ne!(observation.failure_stage, Some(crate::tls::ProbeStreamFailureStage::TlsHandshake));
        assert_eq!(observation.tls_server_hello_received, None);
        assert_eq!(observation.client_hello_capture, ClientHelloCapture::NotSent);
        assert_eq!(observation.ja3_fingerprint, None);
    }

    #[test]
    fn slow_drip_handshake_stops_at_the_scan_deadline() {
        let (port, server) = slow_drip_server(Duration::from_secs(4));
        let started = Instant::now();

        let observation = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(500)), || {
            try_tls_handshake(
                &TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST)),
                port,
                &direct_transport(),
                "localhost",
                TlsClientProfile::Tls13Only,
                None,
            )
        });

        let elapsed = started.elapsed();
        assert_eq!(observation.status, "interrupted");
        assert!(matches!(
            observation.error.as_deref(),
            Some("scan_deadline_exceeded" | "probe_aborted:scan_deadline_exceeded")
        ));
        assert_eq!(observation.failure_stage, None);
        assert_eq!(observation.client_hello_capture, ClientHelloCapture::Complete);
        assert!(observation.ja3_fingerprint.is_some());
        assert!(elapsed < Duration::from_secs(2), "handshake ran for {elapsed:?}");
        server.join().unwrap();
    }
}
