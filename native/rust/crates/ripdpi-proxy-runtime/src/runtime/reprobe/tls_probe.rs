use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use super::super::failure::RuntimeProbeResult;
use super::super::routing::connect_socket;
use super::super::state::RuntimeState;
use super::target_catalog::PROBE_TIMEOUT;

/// Send a baseline TLS ClientHello and check for a ServerHello.
/// This probe does not apply the current desync strategy or complete an authenticated handshake.
pub(crate) fn probe_tls_handshake(target: SocketAddr, sni: &str, protect_path: Option<&str>) -> RuntimeProbeResult {
    let stream =
        match connect_socket(target, IpAddr::V4(Ipv4Addr::UNSPECIFIED), protect_path, false, Some(PROBE_TIMEOUT)) {
            Ok(s) => s,
            Err(err) => return RuntimeState::classify_probe_connect_error(&err),
        };

    if stream.set_read_timeout(Some(PROBE_TIMEOUT)).is_err() {
        return RuntimeProbeResult::NetworkError("set_timeout_failed");
    }
    if stream.set_write_timeout(Some(PROBE_TIMEOUT)).is_err() {
        return RuntimeProbeResult::NetworkError("set_timeout_failed");
    }

    let client_hello = RuntimeState::build_probe_client_hello(sni);
    let mut stream = stream;
    if let Err(err) = stream.write_all(&client_hello) {
        return RuntimeState::classify_probe_write_error(&err);
    }

    let mut header = [0u8; 5];
    match stream.read_exact(&mut header) {
        Ok(()) => match read_handshake_type(&mut stream, header) {
            Ok(handshake_type) => RuntimeState::classify_probe_tls_response(header, handshake_type),
            Err(err) => classify_bounded_read_error(&err),
        },
        Err(err) => classify_bounded_read_error(&err),
    }
}

fn classify_bounded_read_error(error: &io::Error) -> RuntimeProbeResult {
    // This blocking socket has a receive timeout; Unix reports its expiry as WouldBlock.
    if error.kind() == io::ErrorKind::WouldBlock {
        RuntimeProbeResult::DpiFailure("timeout")
    } else {
        RuntimeState::classify_probe_read_error(error)
    }
}

fn read_handshake_type(stream: &mut impl Read, header: [u8; 5]) -> io::Result<Option<u8>> {
    if header[0] != 0x16 {
        return Ok(None);
    }

    let mut handshake_type = [0u8; 1];
    stream.read_exact(&mut handshake_type)?;
    Ok(Some(handshake_type[0]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::net::{TcpListener, TcpStream};
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    fn probe_stalled_response(prefix: Option<[u8; 5]>) -> RuntimeProbeResult {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
        let target = listener.local_addr().expect("origin address");
        let (release, released) = mpsc::channel();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept probe");
            stream.set_read_timeout(Some(Duration::from_secs(7))).expect("bound origin read");
            let mut header = [0; 5];
            stream.read_exact(&mut header).expect("read ClientHello header");
            let mut payload = vec![0; usize::from(u16::from_be_bytes([header[3], header[4]]))];
            stream.read_exact(&mut payload).expect("read ClientHello payload");
            if let Some(header) = prefix {
                stream.write_all(&header).expect("write response header");
            }
            released.recv_timeout(Duration::from_secs(7)).expect("hold origin until probe finishes");
        });
        let result = probe_tls_handshake(target, "localhost", None);
        release.send(()).expect("release origin");
        server.join().expect("origin worker");
        result
    }

    #[test]
    fn blocking_probe_header_timeout_is_reported_as_timeout() {
        assert_eq!(probe_stalled_response(None), RuntimeProbeResult::DpiFailure("timeout"));
    }

    #[test]
    fn blocking_probe_handshake_byte_timeout_is_reported_as_timeout() {
        assert_eq!(
            probe_stalled_response(Some([0x16, 0x03, 0x03, 0x00, 0x04])),
            RuntimeProbeResult::DpiFailure("timeout")
        );
    }

    #[test]
    fn nonblocking_probe_unreadiness_is_not_a_timeout_or_dpi_failure() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
        let mut client = TcpStream::connect(listener.local_addr().expect("origin address")).expect("connect origin");
        let (_origin, _) = listener.accept().expect("accept client");
        client.set_nonblocking(true).expect("nonblocking client");
        let error = client.read(&mut [0; 5]).expect_err("no response is ready");
        assert_eq!(error.kind(), std::io::ErrorKind::WouldBlock);
        assert_eq!(RuntimeState::classify_probe_read_error(&error), RuntimeProbeResult::NetworkError("read_failed"));
    }

    #[test]
    fn read_handshake_type_ignores_non_handshake_records() {
        let mut stream = Cursor::new([0x02]);

        assert_eq!(
            read_handshake_type(&mut stream, [0x15, 0x03, 0x03, 0x00, 0x02]).expect("non-handshake header"),
            None
        );
        assert_eq!(stream.position(), 0);
    }

    #[test]
    fn read_handshake_type_reads_first_handshake_byte() {
        let mut stream = Cursor::new([0x02, 0x00, 0x00, 0x00]);

        assert_eq!(
            read_handshake_type(&mut stream, [0x16, 0x03, 0x03, 0x00, 0x04]).expect("handshake byte"),
            Some(0x02)
        );
        assert_eq!(stream.position(), 1);
    }

    #[test]
    fn read_handshake_type_propagates_missing_payload() {
        let mut stream = Cursor::new([]);

        assert_eq!(
            read_handshake_type(&mut stream, [0x16, 0x03, 0x03, 0x00, 0x01])
                .expect_err("missing handshake byte")
                .kind(),
            io::ErrorKind::UnexpectedEof
        );
    }

    fn probe_rustls_origin(version: &'static rustls::SupportedProtocolVersion) {
        let certificate = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("test certificate");
        let config = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_protocol_versions(&[version])
            .expect("test protocol version")
            .with_no_client_auth()
            .with_single_cert(
                vec![certificate.cert.der().clone()],
                rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into(),
            )
            .expect("test server config");
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
        let target = listener.local_addr().expect("origin address");
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept probe");
            stream.set_read_timeout(Some(Duration::from_secs(7))).expect("bound origin read");
            let mut header = [0; 5];
            stream.read_exact(&mut header).expect("ClientHello header");
            let mut hello = header.to_vec();
            let mut payload = vec![0; usize::from(u16::from_be_bytes([header[3], header[4]]))];
            stream.read_exact(&mut payload).expect("ClientHello payload");
            hello.extend(payload);
            let mut tls = rustls::ServerConnection::new(std::sync::Arc::new(config)).expect("test server");
            tls.read_tls(&mut Cursor::new(hello)).expect("read TLS");
            let result = tls.process_new_packets().map(|_| ());
            let sni = tls.server_name().map(str::to_owned);
            tls.write_tls(&mut stream).expect("write server flight");
            (result, sni)
        });
        let result = probe_tls_handshake(target, "localhost", None);
        let (origin_result, sni) = server.join().expect("origin worker");
        assert_eq!(result, RuntimeProbeResult::Success, "independent origin result: {origin_result:?}");
        assert!(origin_result.is_ok(), "independent origin rejected ClientHello: {origin_result:?}");
        assert_eq!(sni.as_deref(), Some("localhost"));
    }

    #[test]
    fn reprobe_client_hello_is_accepted_by_tls12_origin() {
        probe_rustls_origin(&rustls::version::TLS12);
    }

    #[test]
    fn reprobe_client_hello_is_accepted_by_tls13_origin() {
        probe_rustls_origin(&rustls::version::TLS13);
    }

    #[test]
    fn origin_tls_rejections_do_not_report_dpi_failure() {
        for code in [40, 42, 70, 112, 120] {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
            let target = listener.local_addr().expect("origin address");
            let server = thread::spawn(move || {
                let (mut stream, _) = listener.accept().expect("accept probe");
                stream.set_read_timeout(Some(Duration::from_secs(7))).expect("bound origin read");
                let mut header = [0; 5];
                stream.read_exact(&mut header).expect("ClientHello header");
                let mut payload = vec![0; usize::from(u16::from_be_bytes([header[3], header[4]]))];
                stream.read_exact(&mut payload).expect("ClientHello payload");
                stream.write_all(&[0x15, 0x03, 0x03, 0x00, 0x02, 0x02, code]).expect("origin rejection");
            });
            let result = probe_tls_handshake(target, "localhost", None);
            server.join().expect("origin worker");
            assert_eq!(result, RuntimeProbeResult::NetworkError("tls_rejected"), "origin alert {code}");
        }
    }
}
