use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use super::super::failure::RuntimeProbeResult;
use super::super::routing::connect_socket;
use super::super::state::RuntimeState;
use super::target_catalog::PROBE_TIMEOUT;

/// Attempt a raw TLS ClientHello to `target` and read the first response bytes.
/// Returns `Success` if we get a ServerHello (TLS record type 0x16, handshake
/// type 0x02), `DpiFailure` if we see signs of DPI interference.
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

    let client_hello = build_minimal_client_hello(sni);
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

/// Build a minimal TLS 1.0 ClientHello with the given SNI. This is a stripped-
/// down version -- just enough to trigger DPI SNI classification.
pub(crate) fn build_minimal_client_hello(sni: &str) -> Vec<u8> {
    let sni_bytes = sni.as_bytes();
    let sni_list_len = (sni_bytes.len() + 3) as u16;
    let sni_ext_len = sni_list_len + 2;

    // SNI extension (type 0x0000)
    let mut extensions = Vec::new();
    extensions.extend_from_slice(&[0x00, 0x00]);
    extensions.extend_from_slice(&sni_ext_len.to_be_bytes());
    extensions.extend_from_slice(&sni_list_len.to_be_bytes());
    extensions.push(0x00); // host_name type
    extensions.extend_from_slice(&(sni_bytes.len() as u16).to_be_bytes());
    extensions.extend_from_slice(sni_bytes);

    let mut body = Vec::new();
    body.extend_from_slice(&[0x03, 0x01]); // client_version: TLS 1.0
    body.extend_from_slice(&[0u8; 32]); // Random (32 bytes)
    body.push(0); // session_id length = 0
    body.extend_from_slice(&[0x00, 0x02, 0x00, 0x9c]); // TLS_RSA_WITH_AES_128_GCM_SHA256
    body.extend_from_slice(&[0x01, 0x00]); // Compression methods: null
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(&extensions);

    let handshake_len = body.len() as u32;
    let mut handshake = Vec::with_capacity(4 + body.len());
    handshake.push(0x01); // ClientHello
    handshake.push((handshake_len >> 16) as u8);
    handshake.push((handshake_len >> 8) as u8);
    handshake.push(handshake_len as u8);
    handshake.extend_from_slice(&body);

    let record_len = handshake.len() as u16;
    let mut record = Vec::with_capacity(5 + handshake.len());
    record.push(0x16); // ContentType: Handshake
    record.extend_from_slice(&[0x03, 0x01]); // TLS 1.0
    record.extend_from_slice(&record_len.to_be_bytes());
    record.extend_from_slice(&handshake);

    record
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
}
