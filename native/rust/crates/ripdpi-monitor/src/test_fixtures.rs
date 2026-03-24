use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use golden_test_support::{assert_text_golden, canonicalize_json_with};
use rcgen::generate_simple_self_signed;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use serde_json::Value;

use crate::transport::{decode_socks5_udp_frame, encode_socks5_udp_frame};
use crate::types::ScanReport;
use crate::util::{CONNECT_TIMEOUT, FAT_HEADER_THRESHOLD_BYTES, IO_TIMEOUT};
use crate::{MonitorSession, ScanProgress};

pub(crate) struct UdpDnsServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl UdpDnsServer {
    pub(crate) fn start(answer_ip: &str) -> Self {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp dns");
        socket.set_read_timeout(Some(Duration::from_millis(100))).expect("set udp timeout");
        let addr = socket.local_addr().expect("udp local addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let answer_ip = answer_ip.parse::<Ipv4Addr>().expect("parse answer ip");
        let handle = thread::spawn(move || {
            let mut buf = [0u8; 512];
            while !stop_flag.load(Ordering::Relaxed) {
                match socket.recv_from(&mut buf) {
                    Ok((size, peer)) => {
                        if let Ok(response) = build_udp_dns_answer(&buf[..size], answer_ip) {
                            let _ = socket.send_to(&response, peer);
                        }
                    }
                    Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                    Err(_) => break,
                }
            }
        });
        Self { addr, stop, handle: Some(handle) }
    }

    pub(crate) fn addr(&self) -> String {
        self.addr.to_string()
    }
}

impl Drop for UdpDnsServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let wake = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind wake udp");
        let _ = wake.send_to(b"wake", self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join udp dns");
        }
    }
}

pub(crate) struct Socks5RelayServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    udp_associate_attempts: Arc<AtomicUsize>,
    handle: Option<JoinHandle<()>>,
}

impl Socks5RelayServer {
    pub(crate) fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind socks5 relay");
        listener.set_nonblocking(true).expect("set socks5 relay nonblocking");
        let addr = listener.local_addr().expect("socks5 relay addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let udp_associate_attempts = Arc::new(AtomicUsize::new(0));
        let udp_attempts_ref = udp_associate_attempts.clone();
        let handle = thread::spawn(move || {
            let udp_relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind socks udp relay");
            udp_relay.set_read_timeout(Some(Duration::from_millis(100))).expect("set socks udp timeout");
            let udp_local = udp_relay.local_addr().expect("socks udp local addr");
            let udp_socket = udp_relay.try_clone().expect("clone socks udp relay");
            let udp_stop = stop_flag.clone();
            thread::spawn(move || {
                let mut frame = [0u8; 65535];
                while !udp_stop.load(Ordering::Relaxed) {
                    match udp_socket.recv_from(&mut frame) {
                        Ok((size, peer)) => {
                            if let Ok((destination, payload)) = decode_socks5_udp_frame(&frame[..size]) {
                                let forward = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp forward");
                                forward.set_read_timeout(Some(IO_TIMEOUT)).expect("set udp forward timeout");
                                let _ = forward.send_to(&payload, destination);
                                let mut response = [0u8; 2048];
                                if let Ok((read, from)) = forward.recv_from(&mut response) {
                                    let reply = encode_socks5_udp_frame(from, &response[..read]);
                                    let _ = udp_socket.send_to(&reply, peer);
                                }
                            }
                        }
                        Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                        Err(_) => break,
                    }
                }
            });

            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let udp_attempts_ref = udp_attempts_ref.clone();
                        thread::spawn(move || {
                            let _ = stream.set_nonblocking(false);
                            let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
                            if read_socks_greeting(&mut stream).is_err() {
                                return;
                            }
                            let _ = stream.write_all(&[0x05, 0x00]);

                            let mut header = [0u8; 4];
                            if stream.read_exact(&mut header).is_err() {
                                return;
                            }
                            match header[1] {
                                0x03 => {
                                    udp_attempts_ref.fetch_add(1, Ordering::Relaxed);
                                    if consume_socks_addr(&mut stream, header[3]).is_err() {
                                        return;
                                    }
                                    let reply = encode_socks_reply(udp_local);
                                    let _ = stream.write_all(&reply);
                                    let mut drain = [0u8; 16];
                                    loop {
                                        match stream.read(&mut drain) {
                                            Ok(0) => break,
                                            Ok(_) => {}
                                            Err(err)
                                                if matches!(
                                                    err.kind(),
                                                    ErrorKind::WouldBlock | ErrorKind::TimedOut
                                                ) => {}
                                            Err(_) => break,
                                        }
                                        thread::sleep(Duration::from_millis(20));
                                    }
                                }
                                0x01 => {
                                    let Ok(target) = read_socks_target(&mut stream, header[3]) else {
                                        return;
                                    };
                                    let Ok(upstream) = TcpStream::connect_timeout(&target, CONNECT_TIMEOUT) else {
                                        return;
                                    };
                                    let reply_addr = upstream
                                        .local_addr()
                                        .unwrap_or_else(|_| SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0));
                                    let _ = stream.write_all(&encode_socks_reply(reply_addr));
                                    relay_bidirectional(stream, upstream);
                                }
                                _ => {}
                            }
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });

        Self { addr, stop, udp_associate_attempts, handle: Some(handle) }
    }

    pub(crate) fn port(&self) -> u16 {
        self.addr.port()
    }

    pub(crate) fn udp_associate_attempts(&self) -> usize {
        self.udp_associate_attempts.load(Ordering::Relaxed)
    }
}

impl Drop for Socks5RelayServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        let wake = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind socks wake udp");
        let _ = wake.send_to(b"wake", self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join socks5 relay");
        }
    }
}

pub(crate) fn encode_socks_reply(address: SocketAddr) -> Vec<u8> {
    match address {
        SocketAddr::V4(addr) => {
            let mut reply = vec![0x05, 0x00, 0x00, 0x01];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            reply
        }
        SocketAddr::V6(addr) => {
            let mut reply = vec![0x05, 0x00, 0x00, 0x04];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            reply
        }
    }
}

pub(crate) fn read_socks_greeting(stream: &mut TcpStream) -> Result<(), std::io::Error> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header)?;
    let methods_len = header[1] as usize;
    let mut methods = vec![0u8; methods_len];
    stream.read_exact(&mut methods)?;
    Ok(())
}

pub(crate) fn consume_socks_addr(stream: &mut TcpStream, atyp: u8) -> Result<(), std::io::Error> {
    match atyp {
        0x01 => {
            let mut buf = [0u8; 6];
            stream.read_exact(&mut buf)
        }
        0x04 => {
            let mut buf = [0u8; 18];
            stream.read_exact(&mut buf)
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            let mut buf = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut buf)
        }
        _ => Err(std::io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
    }
}

pub(crate) fn read_socks_target(stream: &mut TcpStream, atyp: u8) -> Result<SocketAddr, std::io::Error> {
    match atyp {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr)?;
            stream.read_exact(&mut port)?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port)))
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            let mut domain = vec![0u8; len[0] as usize];
            let mut port = [0u8; 2];
            stream.read_exact(&mut domain)?;
            stream.read_exact(&mut port)?;
            let domain = String::from_utf8_lossy(&domain).to_string();
            (domain.as_str(), u16::from_be_bytes(port))
                .to_socket_addrs()?
                .next()
                .ok_or_else(|| std::io::Error::new(ErrorKind::AddrNotAvailable, "no target addr"))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr)?;
            stream.read_exact(&mut port)?;
            Ok(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(addr)), u16::from_be_bytes(port)))
        }
        _ => Err(std::io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
    }
}

pub(crate) fn relay_bidirectional(client: TcpStream, upstream: TcpStream) {
    let mut client_reader = client.try_clone().expect("clone client reader");
    let mut client_writer = client;
    let mut upstream_reader = upstream.try_clone().expect("clone upstream reader");
    let mut upstream_writer = upstream;
    let to_upstream = thread::spawn(move || {
        let _ = std::io::copy(&mut client_reader, &mut upstream_writer);
    });
    let _ = std::io::copy(&mut upstream_reader, &mut client_writer);
    let _ = to_upstream.join();
}

pub(crate) struct HttpTextServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl HttpTextServer {
    pub(crate) fn start_text(status_line: &str, body: &str) -> Self {
        let status_line = status_line.to_string();
        let body = body.to_string();
        Self::start(move |_request| {
            format!("{status_line}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).into_bytes()
        })
    }

    pub(crate) fn start_dns_message(answer_ip: &str) -> Self {
        let answer_ip: Ipv4Addr = answer_ip.parse().expect("valid DoH answer IP");
        Self::start(move |mut request| {
            let body = read_http_body(&mut request);
            let response_body = build_udp_dns_answer(&body, answer_ip).expect("build DNS answer");
            let headers = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                response_body.len()
            );
            let mut response = headers.into_bytes();
            response.extend_from_slice(&response_body);
            response
        })
    }

    pub(crate) fn start<F>(handler: F) -> Self
    where
        F: Fn(Vec<u8>) -> Vec<u8> + Send + Sync + 'static,
    {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind http text");
        listener.set_nonblocking(true).expect("set http text nonblocking");
        let addr = listener.local_addr().expect("http text addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handler = Arc::new(handler);
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let handler = handler.clone();
                        thread::spawn(move || {
                            let request = read_http_request(&mut stream);
                            let response = handler(request);
                            let _ = stream.write_all(&response);
                            let _ = stream.flush();
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self { addr, stop, handle: Some(handle) }
    }

    pub(crate) fn port(&self) -> u16 {
        self.addr.port()
    }
}

impl Drop for HttpTextServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join http text");
        }
    }
}

pub(crate) fn wait_for_report(session: &MonitorSession) -> ScanReport {
    for _ in 0..50 {
        if let Some(report_json) = session.take_report_json().expect("take report json") {
            return serde_json::from_str(&report_json).expect("decode scan report");
        }
        thread::sleep(Duration::from_millis(50));
    }
    panic!("timed out waiting for scan report");
}

pub(crate) fn wait_for_progress_json(session: &MonitorSession) -> String {
    let mut finished_progress = None;
    for _ in 0..50 {
        if let Some(progress_json) = session.poll_progress_json().expect("poll progress json") {
            let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode scan progress");
            if !progress.is_finished {
                return progress_json;
            }
            finished_progress = Some(progress_json);
        }
        thread::sleep(Duration::from_millis(20));
    }
    finished_progress.unwrap_or_else(|| panic!("timed out waiting for scan progress"))
}

pub(crate) fn wait_for_report_json(session: &MonitorSession) -> String {
    for _ in 0..50 {
        if let Some(report_json) = session.take_report_json().expect("take report json") {
            return report_json;
        }
        thread::sleep(Duration::from_millis(50));
    }
    panic!("timed out waiting for scan report");
}

pub(crate) fn assert_monitor_json_golden(name: &str, actual_json: &str, http_port: u16) {
    let actual = canonicalize_json_with(actual_json, |value| scrub_monitor_json(value, http_port))
        .expect("canonicalize monitor json");
    assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
}

/// Replace `(os error <number>)` with `(os error _)` so goldens are
/// platform-independent (macOS uses error 61, Linux uses 111, etc.).
fn scrub_os_error_numbers(text: &str) -> String {
    let marker = "(os error ";
    let mut result = String::with_capacity(text.len());
    let mut remaining = text;
    while let Some(start) = remaining.find(marker) {
        result.push_str(&remaining[..start]);
        let after_marker = &remaining[start + marker.len()..];
        if let Some(end) = after_marker.find(')') {
            let digits = &after_marker[..end];
            if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
                result.push_str("(os error _)");
                remaining = &after_marker[end + 1..];
                continue;
            }
        }
        // Not a match -- copy the marker literally and keep scanning.
        result.push_str(marker);
        remaining = after_marker;
    }
    result.push_str(remaining);
    result
}

pub(crate) fn scrub_monitor_json(value: &mut Value, http_port: u16) {
    match value {
        Value::Array(items) => {
            for item in items {
                scrub_monitor_json(item, http_port);
            }
        }
        Value::Object(map) => {
            for (key, item) in map.iter_mut() {
                if matches!(key.as_str(), "startedAt" | "finishedAt" | "createdAt") {
                    *item = Value::from(0);
                } else {
                    scrub_monitor_json(item, http_port);
                }
            }
        }
        Value::String(text) => {
            *text = text.replace(&format!("127.0.0.1:{http_port}"), "127.0.0.1:<port>");
            *text = scrub_os_error_numbers(text);
        }
        _ => {}
    }
}

pub(crate) trait ScanReportExt {
    fn outcome_for(&self, probe_type: &str) -> Option<&str>;
}

impl ScanReportExt for ScanReport {
    fn outcome_for(&self, probe_type: &str) -> Option<&str> {
        self.results.iter().find(|result| result.probe_type == probe_type).map(|result| result.outcome.as_str())
    }
}

#[derive(Clone)]
pub(crate) enum TlsMode {
    Single(String),
}

#[derive(Clone)]
pub(crate) enum FatServerMode {
    AlwaysOk,
    CutoffAtThreshold,
    AllowHost(String),
}

pub(crate) struct TlsHttpServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl TlsHttpServer {
    pub(crate) fn start(mode: TlsMode, fat_mode: FatServerMode) -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tls server");
        listener.set_nonblocking(true).expect("set tls server nonblocking");
        let addr = listener.local_addr().expect("tls server addr");
        let config = match mode {
            TlsMode::Single(name) => {
                let (cert, key) = make_cert(&[name]);
                Arc::new(
                    ServerConfig::builder()
                        .with_no_client_auth()
                        .with_single_cert(vec![cert], key)
                        .expect("single cert config"),
                )
            }
        };
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        stream.set_nonblocking(false).expect("set accepted tls stream blocking");
                        let config = config.clone();
                        let fat_mode = fat_mode.clone();
                        thread::spawn(move || {
                            handle_tls_client(stream, config, fat_mode);
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self { addr, stop, handle: Some(handle) }
    }

    pub(crate) fn port(&self) -> u16 {
        self.addr.port()
    }
}

impl Drop for TlsHttpServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join tls server");
        }
    }
}

pub(crate) struct PlainFatHeaderServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl PlainFatHeaderServer {
    pub(crate) fn start(mode: FatServerMode) -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind plain fat");
        let addr = listener.local_addr().expect("plain fat addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        // Use blocking accept + inline handling to avoid thread-starvation
        // timeouts on resource-constrained hosts (e.g., Raspberry Pi running
        // the full test suite in parallel). Non-blocking accept with a 20 ms
        // poll sleep plus a per-connection thread::spawn added enough latency
        // that clients hit IO_TIMEOUT (1.2 s) before the server responded.
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        if stop_flag.load(Ordering::Relaxed) {
                            break;
                        }
                        handle_plain_fat_client(&mut stream, mode.clone());
                    }
                    Err(_) => break,
                }
            }
        });
        Self { addr, stop, handle: Some(handle) }
    }

    pub(crate) fn port(&self) -> u16 {
        self.addr.port()
    }
}

impl Drop for PlainFatHeaderServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join plain fat");
        }
    }
}

pub(crate) fn handle_tls_client(stream: TcpStream, config: Arc<ServerConfig>, fat_mode: FatServerMode) {
    let Ok(connection) = ServerConnection::new(config) else {
        return;
    };
    let mut stream = StreamOwned::new(connection, stream);
    handle_fat_http_stream(&mut stream, fat_mode);
}

pub(crate) fn handle_plain_fat_client(stream: &mut TcpStream, fat_mode: FatServerMode) {
    // Ensure the stream is blocking with TCP_NODELAY for reliable handling
    // under heavy CPU load (e.g., full workspace test suite on Raspberry Pi).
    stream.set_nonblocking(false).ok();
    stream.set_nodelay(true).ok();
    handle_fat_http_stream(stream, fat_mode);
}

pub(crate) fn handle_fat_http_stream(stream: &mut impl ReadWrite, fat_mode: FatServerMode) {
    let mut total_read = 0usize;
    loop {
        let request = read_until_marker(stream, b"\r\n\r\n");
        if request.is_empty() {
            return;
        }
        total_read += request.len();
        let request_text = String::from_utf8_lossy(&request).to_ascii_lowercase();
        if let FatServerMode::AllowHost(expected) = &fat_mode {
            if !request_text.contains(&expected.to_ascii_lowercase()) {
                return;
            }
        }
        if matches!(&fat_mode, FatServerMode::CutoffAtThreshold) && total_read >= FAT_HEADER_THRESHOLD_BYTES {
            return;
        }
        let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
        let _ = stream.write_all(response);
        let _ = stream.flush();
    }
}

pub(crate) trait ReadWrite: Read + Write {}

impl<T: Read + Write> ReadWrite for T {}

pub(crate) fn read_until_marker(stream: &mut impl Read, marker: &[u8]) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        match stream.read(&mut byte) {
            Ok(0) => break,
            Ok(_) => {
                buf.push(byte[0]);
                if buf.len() >= marker.len() && buf[buf.len() - marker.len()..] == *marker {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    buf
}

pub(crate) fn read_http_request(stream: &mut impl Read) -> Vec<u8> {
    let mut request = read_until_marker(stream, b"\r\n\r\n");
    let header_len =
        request.windows(4).position(|window| window == b"\r\n\r\n").map_or(request.len(), |offset| offset + 4);
    let header_text = String::from_utf8_lossy(&request[..header_len]).into_owned();
    let content_length = header_text
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
        })
        .unwrap_or(0);
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        stream.read_exact(&mut body).expect("read http body");
        request.extend_from_slice(&body);
    }
    request
}

pub(crate) fn read_http_body(request: &mut Vec<u8>) -> Vec<u8> {
    let header_len =
        request.windows(4).position(|window| window == b"\r\n\r\n").map_or(request.len(), |offset| offset + 4);
    let header_text = String::from_utf8_lossy(&request[..header_len]).into_owned();
    let content_length = header_text
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
        })
        .unwrap_or(0);
    let body = request.split_off(header_len);
    if body.len() != content_length {
        panic!("unexpected DoH request body length: expected {content_length}, got {}", body.len());
    }
    body
}

pub(crate) fn build_udp_dns_answer(request: &[u8], answer_ip: Ipv4Addr) -> Result<Vec<u8>, String> {
    if request.len() < 12 {
        return Err("short request".to_string());
    }
    let mut answer = Vec::new();
    answer.extend(&request[0..2]);
    answer.extend(0x8180u16.to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(&request[12..]);
    answer.extend([0xc0, 0x0c]);
    answer.extend(1u16.to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(60u32.to_be_bytes());
    answer.extend(4u16.to_be_bytes());
    answer.extend(answer_ip.octets());
    Ok(answer)
}

pub(crate) fn make_cert(names: &[String]) -> (CertificateDer<'static>, PrivateKeyDer<'static>) {
    let certified = generate_simple_self_signed(names.to_vec()).expect("generate cert");
    let cert = certified.cert.der().clone();
    let key = PrivateKeyDer::Pkcs8(certified.key_pair.serialize_der().into());
    (cert, key)
}
