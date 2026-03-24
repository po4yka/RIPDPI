use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use local_network_fixture::FixtureStack;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, StreamOwned};

use super::socks5::socks_connect_domain;
use super::telemetry::NoCertificateVerification;
use super::SOCKET_TIMEOUT;

// ── FragmentingProfile / FragmentingStream ──

#[derive(Clone, Copy)]
pub struct FragmentingProfile {
    pub max_write: usize,
    pub delay: Duration,
}

impl Default for FragmentingProfile {
    fn default() -> Self {
        Self { max_write: 32, delay: Duration::from_millis(5) }
    }
}

pub struct FragmentingStream {
    inner: TcpStream,
    profile: Option<FragmentingProfile>,
}

impl FragmentingStream {
    pub fn new(inner: TcpStream, profile: FragmentingProfile) -> Self {
        Self { inner, profile: Some(profile) }
    }

    pub fn passthrough(inner: TcpStream) -> Self {
        Self { inner, profile: None }
    }
}

impl Read for FragmentingStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.inner.read(buf)
    }
}

impl Write for FragmentingStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if let Some(profile) = self.profile {
            let capped = profile.max_write.max(1).min(buf.len());
            let written = self.inner.write(&buf[..capped])?;
            if written > 0 && written < buf.len() {
                thread::sleep(profile.delay);
            }
            Ok(written)
        } else {
            self.inner.write(buf)
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

// ── TLS probe ──

pub fn tls_probe(
    stream: TcpStream,
    server_name: &str,
    fragmented: Option<FragmentingProfile>,
) -> Result<String, String> {
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
        .with_no_client_auth();
    let server_name = ServerName::try_from(server_name.to_string()).map_err(|err| err.to_string())?;
    let connection = ClientConnection::new(Arc::new(config), server_name).map_err(|err| err.to_string())?;

    let stream = if let Some(profile) = fragmented {
        FragmentingStream::new(stream, profile)
    } else {
        FragmentingStream::passthrough(stream)
    };
    let mut tls = StreamOwned::new(connection, stream);

    while tls.conn.is_handshaking() {
        tls.conn.complete_io(&mut tls.sock).map_err(|err| err.to_string())?;
    }

    let mut response = Vec::new();
    let mut chunk = [0u8; 256];
    loop {
        match tls.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => response.extend_from_slice(&chunk[..read]),
            Err(err)
                if err.to_string().to_ascii_lowercase().contains("unexpected eof")
                    || err.kind() == io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(err) => return Err(err.to_string()),
        }
    }

    String::from_utf8(response).map_err(|err| err.to_string())
}

// ── TLS round-trip helpers ──

pub fn socks5_tls_round_trip_with_retry(
    proxy_port: u16,
    fixture: &FixtureStack,
    fragmented: Option<&FragmentingProfile>,
) -> String {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_socks5_tls_round_trip(proxy_port, fixture, fragmented) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "socks5 tls round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown socks5 tls error".to_string())
    );
}

pub fn attempt_socks5_tls_round_trip(
    proxy_port: u16,
    fixture: &FixtureStack,
    fragmented: Option<&FragmentingProfile>,
) -> Result<String, String> {
    let (stream, reply) = socks_connect_domain(proxy_port, "127.0.0.1", fixture.manifest().tls_echo_port);
    if reply.get(1).copied() != Some(0x00) {
        return Err(format!("SOCKS5 domain connect failed: {reply:?}"));
    }

    tls_probe(stream, &fixture.manifest().fixture_domain, fragmented.copied())
}

// ── HTTP CONNECT helpers ──

pub fn http_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect http proxy");
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set http connect read timeout");
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).expect("set http connect write timeout");
    write!(stream, "CONNECT 127.0.0.1:{dst_port} HTTP/1.1\r\nHost: 127.0.0.1:{dst_port}\r\n\r\n")
        .expect("write http connect");
    let mut response = Vec::new();
    let mut chunk = [0u8; 1024];
    while !response.windows(4).any(|window| window == b"\r\n\r\n") {
        let read = stream.read(&mut chunk).expect("read http connect reply");
        assert_ne!(read, 0, "http connect response closed early");
        response.extend_from_slice(&chunk[..read]);
    }
    let response = String::from_utf8(response).expect("utf8 connect reply");
    assert!(response.contains("HTTP/1.1 200 OK"), "connect failed: {response}");
    stream
}

pub fn http_connect_round_trip_with_retry(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_http_connect_round_trip(proxy_port, dst_port, payload) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "http connect round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown http connect error".to_string())
    );
}

pub fn attempt_http_connect_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut stream = http_connect(proxy_port, dst_port);
    stream.write_all(payload).map_err(|error| format!("write http connect payload failed: {error}"))?;
    let mut body = vec![0u8; payload.len()];
    stream.read_exact(&mut body).map_err(|error| format!("read http connect payload failed: {error}"))?;
    Ok(body)
}
