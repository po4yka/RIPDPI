use std::fs::{self, File};
use std::io::{Read, Write};
use std::net::{
    IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket,
};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use rcgen::generate_simple_self_signed;
use rustls::pki_types::{PrivateKeyDer, ServerName};
use rustls::{
    ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection, StreamOwned,
};

#[cfg(unix)]
use libc::{kill, SIGTERM};

fn rust_bin() -> &'static str {
    env!("CARGO_BIN_EXE_ciadpi")
}

fn free_port() -> u16 {
    TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .expect("bind ephemeral port")
        .local_addr()
        .expect("local addr")
        .port()
}

fn unique_log_path() -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock")
        .as_nanos();
    std::env::temp_dir().join(format!(
        "ciadpi-runtime-integration-{}-{nanos}.log",
        std::process::id()
    ))
}

fn unique_temp_path(prefix: &str, suffix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock")
        .as_nanos();
    std::env::temp_dir().join(format!("{prefix}-{}-{nanos}{suffix}", std::process::id()))
}

fn recv_exact(stream: &mut TcpStream, size: usize) -> Vec<u8> {
    let mut buf = vec![0u8; size];
    stream.read_exact(&mut buf).expect("read exact bytes");
    buf
}

fn recv_until(stream: &mut impl Read, marker: &[u8]) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 1024];
    while !buf.ends_with(marker) && !buf.windows(marker.len()).any(|window| window == marker) {
        let read = stream.read(&mut chunk).expect("read until marker");
        assert_ne!(read, 0, "stream closed before marker");
        buf.extend_from_slice(&chunk[..read]);
    }
    buf
}

fn recv_socks5_reply(stream: &mut TcpStream) -> Vec<u8> {
    let mut reply = recv_exact(stream, 4);
    let tail = match reply[3] {
        0x01 => recv_exact(stream, 6),
        0x04 => recv_exact(stream, 18),
        0x03 => {
            let mut tail = recv_exact(stream, 1);
            let size = tail[0] as usize;
            tail.extend(recv_exact(stream, size + 2));
            tail
        }
        atyp => panic!("unsupported SOCKS5 reply ATYP: {atyp}"),
    };
    reply.extend(tail);
    reply
}

fn socks_auth(proxy_port: u16) -> TcpStream {
    let mut stream =
        TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream
        .write_all(b"\x05\x01\x00")
        .expect("write auth negotiation");
    assert_eq!(recv_exact(&mut stream, 2), b"\x05\x00");
    stream
}

fn socks_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let mut stream = socks_auth(proxy_port);
    let mut request = Vec::from([0x05, 0x01, 0x00, 0x01]);
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks5 connect");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 connect failed: {reply:?}");
    stream
}

fn socks_connect_ipv6(proxy_port: u16, host: Ipv6Addr, dst_port: u16) -> TcpStream {
    let mut stream = socks_auth(proxy_port);
    let mut request = Vec::from([0x05, 0x01, 0x00, 0x04]);
    request.extend(host.octets());
    request.extend(dst_port.to_be_bytes());
    stream
        .write_all(&request)
        .expect("write socks5 ipv6 connect");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 IPv6 connect failed: {reply:?}");
    stream
}

fn socks_connect_domain(proxy_port: u16, host: &str, dst_port: u16) -> (TcpStream, Vec<u8>) {
    let mut stream = socks_auth(proxy_port);
    let host_bytes = host.as_bytes();
    let mut request = Vec::with_capacity(7 + host_bytes.len());
    request.extend([0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
    request.extend(host_bytes);
    request.extend(dst_port.to_be_bytes());
    stream
        .write_all(&request)
        .expect("write socks5 domain connect");
    let reply = recv_socks5_reply(&mut stream);
    (stream, reply)
}

fn socks4_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let mut stream =
        TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    let mut request = Vec::from([0x04, 0x01]);
    request.extend(dst_port.to_be_bytes());
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(b"user\x00");
    stream.write_all(&request).expect("write socks4 connect");
    let reply = recv_exact(&mut stream, 8);
    assert_eq!(reply[1], 0x5a, "SOCKS4 connect failed: {reply:?}");
    stream
}

fn http_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let mut stream =
        TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    write!(
        stream,
        "CONNECT 127.0.0.1:{dst_port} HTTP/1.1\r\nHost: 127.0.0.1:{dst_port}\r\n\r\n"
    )
    .expect("write http connect");
    let mut response = Vec::new();
    let mut chunk = [0u8; 1024];
    while !response.ends_with(b"\r\n\r\n") {
        let read = stream.read(&mut chunk).expect("read http connect reply");
        assert_ne!(read, 0, "http connect response closed early");
        response.extend_from_slice(&chunk[..read]);
    }
    let response = String::from_utf8(response).expect("utf8 http connect reply");
    assert!(
        response.contains("HTTP/1.1 200 OK"),
        "http connect failed: {response}"
    );
    stream
}

fn http_connect_raw(proxy_port: u16, request: &str) -> String {
    let mut stream =
        TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream
        .write_all(request.as_bytes())
        .expect("write raw http connect request");
    String::from_utf8(recv_until(&mut stream, b"\r\n\r\n")).expect("utf8 http connect reply")
}

fn parse_socks5_reply_addr(reply: &[u8]) -> SocketAddr {
    match reply[3] {
        0x01 => SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7])),
            u16::from_be_bytes([reply[8], reply[9]]),
        ),
        0x04 => SocketAddr::new(
            IpAddr::V6(Ipv6Addr::from([
                reply[4], reply[5], reply[6], reply[7], reply[8], reply[9], reply[10], reply[11],
                reply[12], reply[13], reply[14], reply[15], reply[16], reply[17], reply[18],
                reply[19],
            ])),
            u16::from_be_bytes([reply[20], reply[21]]),
        ),
        atyp => panic!("unsupported SOCKS5 reply address type: {atyp}"),
    }
}

fn socks_udp_associate(proxy_port: u16) -> (TcpStream, SocketAddr) {
    let mut stream = socks_auth(proxy_port);
    let mut request = Vec::from([0x05, 0x03, 0x00, 0x01]);
    request.extend([0, 0, 0, 0]);
    request.extend([0, 0]);
    stream.write_all(&request).expect("write udp associate");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 UDP associate failed: {reply:?}");
    let relay = parse_socks5_reply_addr(&reply);
    (stream, relay)
}

fn udp_proxy_roundtrip(relay: SocketAddr, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp client");
    socket
        .set_read_timeout(Some(Duration::from_secs(5)))
        .expect("set udp client timeout");
    let mut packet = Vec::from([0x00, 0x00, 0x00, 0x01]);
    packet.extend(Ipv4Addr::LOCALHOST.octets());
    packet.extend(dst_port.to_be_bytes());
    packet.extend(payload);
    socket
        .send_to(&packet, relay)
        .expect("send udp packet through proxy");
    let mut buf = [0u8; 4096];
    loop {
        let (read, _) = socket.recv_from(&mut buf).expect("recv udp proxy response");
        assert!(read >= 10, "udp response too short: {read}");
        assert_eq!(&buf[..4], b"\x00\x00\x00\x01");
        let body = &buf[10..read];
        if body == payload {
            return body.to_vec();
        }
    }
}

struct EchoServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl EchoServer {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind echo server");
        listener
            .set_nonblocking(true)
            .expect("set nonblocking echo listener");
        let addr = listener.local_addr().expect("echo listener addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        thread::spawn(move || {
                            let mut buf = [0u8; 4096];
                            loop {
                                match stream.read(&mut buf) {
                                    Ok(0) => return,
                                    Ok(read) => {
                                        if stream.write_all(&buf[..read]).is_err() {
                                            return;
                                        }
                                    }
                                    Err(err) if err.kind() == std::io::ErrorKind::Interrupted => {}
                                    Err(_) => return,
                                }
                            }
                        });
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            addr,
            stop,
            handle: Some(handle),
        }
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }
}

impl Drop for EchoServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join echo listener");
        }
    }
}

struct EchoServerV6 {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl EchoServerV6 {
    fn start() -> Option<Self> {
        let listener = TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).ok()?;
        listener.set_nonblocking(true).ok()?;
        let addr = listener.local_addr().ok()?;
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        thread::spawn(move || {
                            let mut buf = [0u8; 4096];
                            loop {
                                match stream.read(&mut buf) {
                                    Ok(0) => return,
                                    Ok(read) => {
                                        if stream.write_all(&buf[..read]).is_err() {
                                            return;
                                        }
                                    }
                                    Err(err) if err.kind() == std::io::ErrorKind::Interrupted => {}
                                    Err(_) => return,
                                }
                            }
                        });
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Some(Self {
            addr,
            stop,
            handle: Some(handle),
        })
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }
}

impl Drop for EchoServerV6 {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join ipv6 echo listener");
        }
    }
}

struct UdpEchoServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl UdpEchoServer {
    fn start() -> Self {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp echo server");
        socket
            .set_read_timeout(Some(Duration::from_millis(100)))
            .expect("set udp echo timeout");
        let addr = socket.local_addr().expect("udp echo listener addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            let mut buf = [0u8; 4096];
            while !stop_flag.load(Ordering::Relaxed) {
                match socket.recv_from(&mut buf) {
                    Ok((read, peer)) => {
                        let _ = socket.send_to(&buf[..read], peer);
                    }
                    Err(err)
                        if matches!(
                            err.kind(),
                            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                        ) => {}
                    Err(_) => break,
                }
            }
        });
        Self {
            addr,
            stop,
            handle: Some(handle),
        }
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }
}

impl Drop for UdpEchoServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let wake = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp wake socket");
        let _ = wake.send_to(b"wake", self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join udp echo listener");
        }
    }
}

struct RecordingUdpServer {
    addr: SocketAddr,
    packets: Arc<Mutex<Vec<Vec<u8>>>>,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl RecordingUdpServer {
    fn start() -> Self {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind recording udp server");
        socket
            .set_read_timeout(Some(Duration::from_millis(100)))
            .expect("set recording udp timeout");
        let addr = socket.local_addr().expect("recording udp listener addr");
        let stop = Arc::new(AtomicBool::new(false));
        let packets = Arc::new(Mutex::new(Vec::new()));
        let stop_flag = stop.clone();
        let packets_ref = packets.clone();
        let handle = thread::spawn(move || {
            let mut buf = [0u8; 4096];
            while !stop_flag.load(Ordering::Relaxed) {
                match socket.recv_from(&mut buf) {
                    Ok((read, peer)) => {
                        packets_ref
                            .lock()
                            .expect("lock recording packets")
                            .push(buf[..read].to_vec());
                        let _ = socket.send_to(&buf[..read], peer);
                    }
                    Err(err)
                        if matches!(
                            err.kind(),
                            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                        ) => {}
                    Err(_) => break,
                }
            }
        });
        Self {
            addr,
            packets,
            stop,
            handle: Some(handle),
        }
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }

    fn packets(&self) -> Vec<Vec<u8>> {
        self.packets.lock().expect("lock recorded packets").clone()
    }
}

impl Drop for RecordingUdpServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let wake =
            UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind recording udp wake socket");
        let _ = wake.send_to(b"wake", self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join recording udp listener");
        }
    }
}

struct TlsHttpServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
    cert_der: rustls::pki_types::CertificateDer<'static>,
}

impl TlsHttpServer {
    fn start() -> Self {
        let certified = generate_simple_self_signed(vec!["localhost".to_string()])
            .expect("generate self-signed tls cert");
        let cert_der = certified.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(certified.key_pair.serialize_der().into());
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![cert_der.clone()], key_der)
                .expect("build tls server config"),
        );

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tls http server");
        listener
            .set_nonblocking(true)
            .expect("set tls http server nonblocking");
        let addr = listener.local_addr().expect("tls http server addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let config = server_config.clone();
                        thread::spawn(move || {
                            let connection = ServerConnection::new(config)
                                .expect("create tls server connection");
                            let mut stream = StreamOwned::new(connection, stream);
                            let request = recv_until(&mut stream, b"\r\n\r\n");
                            assert!(
                                request
                                    .windows(b"GET / HTTP/1.1".len())
                                    .any(|window| window == b"GET / HTTP/1.1"),
                                "unexpected TLS request payload: {:?}",
                                String::from_utf8_lossy(&request)
                            );
                            stream
                                .write_all(
                                    b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nproxy tls ok",
                                )
                                .expect("write tls http response");
                            stream.flush().expect("flush tls http response");
                        });
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            addr,
            stop,
            handle: Some(handle),
            cert_der,
        }
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }

    fn cert_der(&self) -> rustls::pki_types::CertificateDer<'static> {
        self.cert_der.clone()
    }
}

impl Drop for TlsHttpServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join tls http listener");
        }
    }
}

fn relay_pipe(mut src: TcpStream, mut dst: TcpStream) {
    let mut buf = [0u8; 4096];
    loop {
        match src.read(&mut buf) {
            Ok(0) => return,
            Ok(read) => {
                if dst.write_all(&buf[..read]).is_err() {
                    return;
                }
            }
            Err(_) => return,
        }
    }
}

struct UpstreamSocksServer {
    addr: SocketAddr,
    attempts: Arc<AtomicUsize>,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl UpstreamSocksServer {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks");
        listener
            .set_nonblocking(true)
            .expect("set upstream socks nonblocking");
        let addr = listener.local_addr().expect("upstream socks addr");
        let attempts = Arc::new(AtomicUsize::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let attempts_ref = attempts.clone();
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut client, _)) => {
                        let attempts_ref = attempts_ref.clone();
                        thread::spawn(move || {
                            attempts_ref.fetch_add(1, Ordering::Relaxed);
                            if handle_upstream_socks_client(&mut client).is_err() {
                                let _ = client.shutdown(Shutdown::Both);
                            }
                        });
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            addr,
            attempts,
            stop,
            handle: Some(handle),
        }
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }

    fn attempts(&self) -> usize {
        self.attempts.load(Ordering::Relaxed)
    }
}

impl Drop for UpstreamSocksServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join upstream socks listener");
        }
    }
}

struct FailingSocksServer {
    addr: SocketAddr,
    attempts: Arc<AtomicUsize>,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl FailingSocksServer {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind failing socks");
        listener
            .set_nonblocking(true)
            .expect("set failing socks nonblocking");
        let addr = listener.local_addr().expect("failing socks addr");
        let attempts = Arc::new(AtomicUsize::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let attempts_ref = attempts.clone();
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((_client, _)) => {
                        attempts_ref.fetch_add(1, Ordering::Relaxed);
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            addr,
            attempts,
            stop,
            handle: Some(handle),
        }
    }

    fn port(&self) -> u16 {
        self.addr.port()
    }

    fn attempts(&self) -> usize {
        self.attempts.load(Ordering::Relaxed)
    }
}

impl Drop for FailingSocksServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join failing socks listener");
        }
    }
}

fn handle_upstream_socks_client(client: &mut TcpStream) -> std::io::Result<()> {
    let methods_len = recv_exact(client, 2);
    if methods_len.first().copied() != Some(0x05) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "unexpected upstream socks version",
        ));
    }
    let method_count = methods_len[1] as usize;
    let _ = recv_exact(client, method_count);
    client.write_all(b"\x05\x00")?;

    let header = recv_exact(client, 4);
    if header[0] != 0x05 || header[1] != 0x01 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "unexpected upstream socks command",
        ));
    }
    let target = match header[3] {
        0x01 => {
            let addr = recv_exact(client, 6);
            SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(addr[0], addr[1], addr[2], addr[3])),
                u16::from_be_bytes([addr[4], addr[5]]),
            )
        }
        0x04 => {
            let addr = recv_exact(client, 18);
            SocketAddr::new(
                IpAddr::V6(Ipv6Addr::from([
                    addr[0], addr[1], addr[2], addr[3], addr[4], addr[5], addr[6], addr[7],
                    addr[8], addr[9], addr[10], addr[11], addr[12], addr[13], addr[14], addr[15],
                ])),
                u16::from_be_bytes([addr[16], addr[17]]),
            )
        }
        atyp => {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("unsupported upstream socks atyp: {atyp}"),
            ))
        }
    };

    let upstream = TcpStream::connect(target)?;
    client.write_all(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")?;

    let upstream_reader = upstream.try_clone()?;
    let client_reader = client.try_clone()?;
    let to_client = thread::spawn(move || relay_pipe(upstream_reader, client_reader));
    relay_pipe(client.try_clone()?, upstream);
    let _ = to_client.join();
    Ok(())
}

struct ProxyProcess {
    port: u16,
    child: Child,
    log_path: PathBuf,
}

impl ProxyProcess {
    fn start(extra_args: &[&str]) -> Self {
        Self::start_with_conn_ip(extra_args, "127.0.0.1")
    }

    fn start_with_conn_ip(extra_args: &[&str], conn_ip: &str) -> Self {
        Self::start_from_args(extra_args.iter().copied(), conn_ip)
    }

    fn start_owned(extra_args: Vec<String>) -> Self {
        Self::start_configured(free_port(), true, "127.0.0.1", extra_args, Vec::new())
    }

    fn start_env(port: u16, env_updates: Vec<(String, String)>) -> Self {
        Self::start_configured(port, false, "127.0.0.1", Vec::new(), env_updates)
    }

    fn start_from_args<'a>(extra_args: impl IntoIterator<Item = &'a str>, conn_ip: &str) -> Self {
        Self::start_configured(
            free_port(),
            true,
            conn_ip,
            extra_args.into_iter().map(str::to_owned).collect(),
            Vec::new(),
        )
    }

    fn start_configured(
        port: u16,
        include_listen_args: bool,
        conn_ip: &str,
        extra_args: Vec<String>,
        env_updates: Vec<(String, String)>,
    ) -> Self {
        let log_path = unique_log_path();
        let stdout = File::create(&log_path).expect("create proxy log");
        let stderr = stdout.try_clone().expect("clone proxy log");
        let mut command = Command::new(rust_bin());
        if include_listen_args {
            command
                .arg("-i")
                .arg("127.0.0.1")
                .arg("-p")
                .arg(port.to_string())
                .arg("-I")
                .arg(conn_ip);
        }
        command
            .args(extra_args)
            .stdout(Stdio::from(stdout))
            .stderr(Stdio::from(stderr));
        for (key, value) in env_updates {
            command.env(key, value);
        }
        let child = command.spawn().expect("spawn proxy");
        let mut process = Self {
            port,
            child,
            log_path,
        };
        process.wait_ready();
        process
    }

    fn wait_ready(&mut self) {
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if let Ok(probe) = TcpStream::connect((Ipv4Addr::LOCALHOST, self.port)) {
                let _ = probe.shutdown(Shutdown::Both);
                return;
            }
            if self.child.try_wait().expect("poll proxy status").is_some() {
                panic!("proxy exited early:\n{}", self.read_log());
            }
            thread::sleep(Duration::from_millis(50));
        }
        panic!("proxy did not start:\n{}", self.read_log());
    }

    fn read_log(&self) -> String {
        fs::read_to_string(&self.log_path).unwrap_or_default()
    }

    fn pid(&self) -> u32 {
        self.child.id()
    }

    #[cfg(unix)]
    fn terminate(&mut self) {
        if self.child.try_wait().expect("poll proxy status").is_none() {
            let rc = unsafe { kill(self.child.id() as i32, SIGTERM) };
            assert_eq!(rc, 0, "send SIGTERM to proxy");
            let deadline = Instant::now() + Duration::from_secs(5);
            while Instant::now() < deadline {
                if self.child.try_wait().expect("poll proxy status").is_some() {
                    return;
                }
                thread::sleep(Duration::from_millis(50));
            }
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
}

impl Drop for ProxyProcess {
    fn drop(&mut self) {
        if self.child.try_wait().expect("poll proxy status").is_none() {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
        let _ = fs::remove_file(&self.log_path);
    }
}

#[test]
fn socks5_echo_round_trip() {
    let echo = EchoServer::start();
    let proxy = ProxyProcess::start(&[]);
    let mut stream = socks_connect(proxy.port, echo.port());
    let payload = b"plain socks payload";
    stream.write_all(payload).expect("write echo payload");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);
}

#[test]
fn socks5_ipv6_echo_round_trip() {
    let Some(echo) = EchoServerV6::start() else {
        return;
    };
    let proxy = ProxyProcess::start_with_conn_ip(&[], "::");
    let mut stream = socks_connect_ipv6(proxy.port, Ipv6Addr::LOCALHOST, echo.port());
    let payload = b"plain socks ipv6 payload";
    stream.write_all(payload).expect("write ipv6 echo payload");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);
}

#[test]
fn socks5_tls_tunnel_round_trip() {
    let tls_server = TlsHttpServer::start();
    let proxy = ProxyProcess::start(&[]);
    let raw_stream = socks_connect(proxy.port, tls_server.port());

    let mut roots = RootCertStore::empty();
    roots
        .add(tls_server.cert_der())
        .expect("add tls server cert to roots");
    let client_config = Arc::new(
        ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth(),
    );
    let server_name = ServerName::try_from("localhost")
        .expect("valid tls server name")
        .to_owned();
    let connection =
        ClientConnection::new(client_config, server_name).expect("create tls client connection");
    let mut tls_stream = StreamOwned::new(connection, raw_stream);

    tls_stream
        .write_all(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .expect("write tls http request");
    let response = recv_until(&mut tls_stream, b"proxy tls ok");
    assert!(
        response
            .windows(b"HTTP/1.1 200 OK".len())
            .any(|window| window == b"HTTP/1.1 200 OK"),
        "unexpected tls tunnel response: {:?}",
        String::from_utf8_lossy(&response)
    );
    assert!(response.ends_with(b"proxy tls ok"));
}

#[test]
fn socks4_echo_round_trip() {
    let echo = EchoServer::start();
    let proxy = ProxyProcess::start(&[]);
    let mut stream = socks4_connect(proxy.port, echo.port());
    let payload = b"plain socks4 payload";
    stream.write_all(payload).expect("write echo payload");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);
}

#[test]
fn http_connect_echo_round_trip() {
    let echo = EchoServer::start();
    let proxy = ProxyProcess::start(&["-G"]);
    let mut stream = http_connect(proxy.port, echo.port());
    let payload = b"http connect payload";
    stream.write_all(payload).expect("write connect payload");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);
}

#[test]
fn http_connect_failure_returns_503() {
    let proxy = ProxyProcess::start(&["-G"]);
    let closed_port = free_port();
    let response = http_connect_raw(
        proxy.port,
        &format!(
            "CONNECT 127.0.0.1:{closed_port} HTTP/1.1\r\nHost: 127.0.0.1:{closed_port}\r\n\r\n"
        ),
    );
    assert!(
        response.contains("HTTP/1.1 503 Fail"),
        "unexpected HTTP CONNECT failure response: {response}"
    );
}

#[test]
fn invalid_http_connect_request_returns_503() {
    let proxy = ProxyProcess::start(&["-G"]);
    let response = http_connect_raw(proxy.port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
    assert!(
        response.contains("HTTP/1.1 503 Fail"),
        "unexpected invalid HTTP CONNECT response: {response}"
    );
}

#[test]
fn no_domain_rejects_domain_requests() {
    let proxy = ProxyProcess::start(&["-N"]);
    let (_stream, reply) = socks_connect_domain(proxy.port, "localhost", 80);
    assert_ne!(
        reply[1], 0,
        "domain request unexpectedly succeeded: {reply:?}"
    );
}

#[test]
fn no_udp_rejects_udp_associate() {
    let proxy = ProxyProcess::start(&["-U"]);
    let mut stream = socks_auth(proxy.port);
    let mut request = Vec::from([0x05, 0x03, 0x00, 0x01]);
    request.extend([0, 0, 0, 0]);
    request.extend([0, 0]);
    stream.write_all(&request).expect("write udp associate");
    let reply = recv_socks5_reply(&mut stream);
    assert_ne!(
        reply[1], 0,
        "udp associate unexpectedly succeeded: {reply:?}"
    );
}

#[test]
fn socks5_udp_associate_round_trip() {
    let echo = UdpEchoServer::start();
    let proxy = ProxyProcess::start(&[]);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let payload = b"udp proxy payload";
    assert_eq!(udp_proxy_roundtrip(relay, echo.port(), payload), payload);
}

#[test]
fn udp_fake_burst_reaches_server_before_payload() {
    let echo = RecordingUdpServer::start();
    let proxy = ProxyProcess::start(&["--udp-fake", "2"]);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let payload = b"udp payload after fakes";
    assert_eq!(udp_proxy_roundtrip(relay, echo.port(), payload), payload);
    let packets = echo.packets();
    assert!(
        packets.len() >= 3,
        "expected at least 3 packets, got {}",
        packets.len()
    );
    assert_eq!(packets[0], vec![0u8; 64]);
    assert_eq!(packets[1], vec![0u8; 64]);
    assert_eq!(packets.last().expect("final packet"), payload);
}

#[test]
fn connect_failure_does_not_create_a_working_tunnel() {
    let closed_port = free_port();
    let proxy = ProxyProcess::start(&[]);
    let mut stream = socks_auth(proxy.port);
    let mut request = Vec::from([0x05, 0x01, 0x00, 0x01]);
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(closed_port.to_be_bytes());
    stream
        .write_all(&request)
        .expect("write socks5 connect request");
    let reply = recv_socks5_reply(&mut stream);
    if reply[1] != 0 {
        return;
    }
    stream
        .set_read_timeout(Some(Duration::from_secs(1)))
        .expect("set read timeout");
    stream
        .write_all(b"closed-port-probe")
        .expect("write probe payload");
    let mut buf = [0u8; 1];
    match stream.read(&mut buf) {
        Ok(0) => {}
        Err(_) => {}
        Ok(read) => panic!("connect failure yielded a working tunnel: read {read} bytes"),
    }
}

#[test]
fn max_conn_limit_rejects_excess_connections() {
    let echo = EchoServer::start();
    let proxy = ProxyProcess::start(&["--max-conn", "1"]);
    let deadline = Instant::now() + Duration::from_secs(2);
    let first = loop {
        if let Ok(mut stream) = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy.port)) {
            stream
                .write_all(b"\x05\x01\x00")
                .expect("write initial auth negotiation");
            let mut auth_reply = [0u8; 2];
            match stream.read(&mut auth_reply) {
                Ok(2) if auth_reply == [0x05, 0x00] => {
                    let mut request = Vec::from([0x05, 0x01, 0x00, 0x01]);
                    request.extend(Ipv4Addr::LOCALHOST.octets());
                    request.extend(echo.port().to_be_bytes());
                    stream
                        .write_all(&request)
                        .expect("write first connect request");
                    let reply = recv_socks5_reply(&mut stream);
                    assert_eq!(reply[1], 0, "SOCKS5 connect failed: {reply:?}");
                    break stream;
                }
                Ok(_) | Err(_) => {}
            }
        }
        assert!(
            Instant::now() < deadline,
            "timed out acquiring first max-conn slot"
        );
        thread::sleep(Duration::from_millis(50));
    };
    let mut second =
        TcpStream::connect((Ipv4Addr::LOCALHOST, proxy.port)).expect("connect second client");
    second
        .set_read_timeout(Some(Duration::from_secs(2)))
        .expect("set second read timeout");
    second
        .write_all(b"\x05\x01\x00")
        .expect("write second auth negotiation");
    let mut auth_reply = [0u8; 2];
    let read_result = second.read(&mut auth_reply);
    drop(first);
    if let Ok(2) = read_result {
        assert_ne!(
            auth_reply,
            [0x05, 0x00],
            "max-conn allowed second SOCKS auth reply"
        );
    }
}

#[test]
fn connection_churn_echo_round_trip() {
    let echo = EchoServer::start();
    let proxy = ProxyProcess::start(&[]);
    for idx in 0..25 {
        let mut stream = socks_connect(proxy.port, echo.port());
        let payload = format!("burst-{idx}");
        stream
            .write_all(payload.as_bytes())
            .expect("write churn payload");
        assert_eq!(recv_exact(&mut stream, payload.len()), payload.as_bytes());
    }
}

#[test]
fn delay_conn_waits_for_first_payload_before_upstream_connect() {
    let echo = EchoServer::start();
    let upstream = UpstreamSocksServer::start();
    let proxy = ProxyProcess::start_owned(vec![
        "--hosts".to_string(),
        ":delayed.example.test".to_string(),
        "--to-socks5".to_string(),
        format!("127.0.0.1:{}", upstream.port()),
    ]);
    let mut stream = socks_connect(proxy.port, echo.port());
    assert_eq!(upstream.attempts(), 0, "upstream connected before payload");

    let payload = b"GET / HTTP/1.1\r\nHost: delayed.example.test\r\n\r\n";
    stream
        .write_all(payload)
        .expect("write delayed-connect payload");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);

    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline && upstream.attempts() == 0 {
        thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(
        upstream.attempts(),
        1,
        "upstream connect did not occur after payload"
    );
}

#[test]
fn external_socks_chain_round_trip() {
    let echo = EchoServer::start();
    let upstream = ProxyProcess::start(&[]);
    let proxy = ProxyProcess::start_owned(vec![
        "-C".to_string(),
        format!("127.0.0.1:{}", upstream.port),
    ]);
    let mut stream = socks_connect(proxy.port, echo.port());
    let payload = b"chained socks payload";
    stream.write_all(payload).expect("write chain payload");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);
}

#[test]
fn shadowsocks_env_mode_tunnels_initial_payload() {
    let echo = EchoServer::start();
    let port = free_port();
    let proxy =
        ProxyProcess::start_env(port, vec![("SS_LOCAL_PORT".to_string(), port.to_string())]);
    let mut stream =
        TcpStream::connect((Ipv4Addr::LOCALHOST, proxy.port)).expect("connect to shadowsocks mode");
    let payload = b"shadow";
    let mut request = Vec::from([0x01]);
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(echo.port().to_be_bytes());
    request.extend(payload);
    stream
        .write_all(&request)
        .expect("write shadowsocks bootstrap request");
    assert_eq!(recv_exact(&mut stream, payload.len()), payload);
}

#[cfg(unix)]
#[test]
fn pidfile_is_created_and_removed() {
    let pidfile = unique_temp_path("ciadpi-pidfile", ".pid");
    let pidfile_arg = pidfile.display().to_string();
    let mut proxy = ProxyProcess::start(&["--pidfile", &pidfile_arg]);

    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline && !pidfile.exists() {
        thread::sleep(Duration::from_millis(50));
    }
    assert!(
        pidfile.exists(),
        "pidfile was not created:\n{}",
        proxy.read_log()
    );
    let pid_text = fs::read_to_string(&pidfile).expect("read pidfile");
    assert_eq!(pid_text.trim(), proxy.pid().to_string());

    proxy.terminate();
    assert!(
        !pidfile.exists(),
        "pidfile was not removed after shutdown:\n{}",
        proxy.read_log()
    );
}

#[cfg(unix)]
#[test]
fn cache_file_stdout_dumps_entries_on_shutdown() {
    let echo = EchoServer::start();
    let failing = FailingSocksServer::start();
    let mut proxy = ProxyProcess::start_owned(vec![
        "--to-socks5".to_string(),
        format!("127.0.0.1:{}", failing.port()),
        "--auto".to_string(),
        "conn".to_string(),
        "--cache-file".to_string(),
        "-".to_string(),
        "--cache-ttl".to_string(),
        "60".to_string(),
    ]);

    let mut stream = socks_connect(proxy.port, echo.port());
    stream.write_all(b"cache").expect("write cache payload");
    assert_eq!(recv_exact(&mut stream, 5), b"cache");
    drop(stream);
    assert_eq!(
        failing.attempts(),
        1,
        "failing upstream was not attempted exactly once"
    );

    proxy.terminate();
    let log = proxy.read_log();
    assert!(
        log.contains(&format!(" 127.0.0.1 32 {} ", echo.port())),
        "cache dump missing expected entry:\n{log}"
    );
}
