use std::fmt;
use std::io::{self, ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rcgen::generate_simple_self_signed;
use rustls::pki_types::PrivateKeyDer;
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use serde::{Deserialize, Serialize};

const IO_POLL_DELAY: Duration = Duration::from_millis(20);
const IO_TIMEOUT: Duration = Duration::from_millis(200);
const SOCKS_IO_TIMEOUT: Duration = Duration::from_secs(3);

pub const DEFAULT_BIND_HOST: &str = "127.0.0.1";
pub const DEFAULT_TCP_ECHO_PORT: u16 = 46001;
pub const DEFAULT_UDP_ECHO_PORT: u16 = 46002;
pub const DEFAULT_TLS_ECHO_PORT: u16 = 46003;
pub const DEFAULT_DNS_UDP_PORT: u16 = 46053;
pub const DEFAULT_DNS_HTTP_PORT: u16 = 46054;
pub const DEFAULT_SOCKS5_PORT: u16 = 46080;
pub const DEFAULT_CONTROL_PORT: u16 = 46090;
pub const DEFAULT_FIXTURE_DOMAIN: &str = "fixture.test";
pub const DEFAULT_FIXTURE_IPV4: &str = "198.18.0.10";
pub const DEFAULT_DNS_ANSWER_IPV4: &str = "198.18.0.10";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureConfig {
    pub bind_host: String,
    pub tcp_echo_port: u16,
    pub udp_echo_port: u16,
    pub tls_echo_port: u16,
    pub dns_udp_port: u16,
    pub dns_http_port: u16,
    pub socks5_port: u16,
    pub control_port: u16,
    pub fixture_domain: String,
    pub fixture_ipv4: String,
    pub dns_answer_ipv4: String,
}

impl Default for FixtureConfig {
    fn default() -> Self {
        Self {
            bind_host: DEFAULT_BIND_HOST.to_string(),
            tcp_echo_port: DEFAULT_TCP_ECHO_PORT,
            udp_echo_port: DEFAULT_UDP_ECHO_PORT,
            tls_echo_port: DEFAULT_TLS_ECHO_PORT,
            dns_udp_port: DEFAULT_DNS_UDP_PORT,
            dns_http_port: DEFAULT_DNS_HTTP_PORT,
            socks5_port: DEFAULT_SOCKS5_PORT,
            control_port: DEFAULT_CONTROL_PORT,
            fixture_domain: DEFAULT_FIXTURE_DOMAIN.to_string(),
            fixture_ipv4: DEFAULT_FIXTURE_IPV4.to_string(),
            dns_answer_ipv4: DEFAULT_DNS_ANSWER_IPV4.to_string(),
        }
    }
}

impl FixtureConfig {
    pub fn from_env() -> Self {
        let mut config = Self::default();
        config.bind_host = env_string("RIPDPI_FIXTURE_BIND_HOST", &config.bind_host);
        config.tcp_echo_port = env_u16("RIPDPI_FIXTURE_TCP_ECHO_PORT", config.tcp_echo_port);
        config.udp_echo_port = env_u16("RIPDPI_FIXTURE_UDP_ECHO_PORT", config.udp_echo_port);
        config.tls_echo_port = env_u16("RIPDPI_FIXTURE_TLS_ECHO_PORT", config.tls_echo_port);
        config.dns_udp_port = env_u16("RIPDPI_FIXTURE_DNS_UDP_PORT", config.dns_udp_port);
        config.dns_http_port = env_u16("RIPDPI_FIXTURE_DNS_HTTP_PORT", config.dns_http_port);
        config.socks5_port = env_u16("RIPDPI_FIXTURE_SOCKS5_PORT", config.socks5_port);
        config.control_port = env_u16("RIPDPI_FIXTURE_CONTROL_PORT", config.control_port);
        config.fixture_domain = env_string("RIPDPI_FIXTURE_DOMAIN", &config.fixture_domain);
        config.fixture_ipv4 = env_string("RIPDPI_FIXTURE_IPV4", &config.fixture_ipv4);
        config.dns_answer_ipv4 = env_string("RIPDPI_FIXTURE_DNS_ANSWER_IPV4", &config.dns_answer_ipv4);
        config
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureManifest {
    pub bind_host: String,
    pub android_host: String,
    pub tcp_echo_port: u16,
    pub udp_echo_port: u16,
    pub tls_echo_port: u16,
    pub dns_udp_port: u16,
    pub dns_http_port: u16,
    pub socks5_port: u16,
    pub control_port: u16,
    pub fixture_domain: String,
    pub fixture_ipv4: String,
    pub dns_answer_ipv4: String,
    pub tls_certificate_pem: String,
}

impl FixtureManifest {
    pub fn control_url_for_host(&self, host: &str) -> String {
        format!("http://{host}:{}", self.control_port)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureEvent {
    pub service: String,
    pub protocol: String,
    pub peer: String,
    pub target: String,
    pub detail: String,
    pub bytes: usize,
    pub sni: Option<String>,
    pub created_at: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FixtureFaultScope {
    OneShot,
    Persistent,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FixtureFaultTarget {
    TcpEcho,
    UdpEcho,
    TlsEcho,
    DnsUdp,
    DnsHttp,
    Socks5Relay,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FixtureFaultOutcome {
    TcpReset,
    TcpTruncate,
    UdpDrop,
    UdpDelay,
    TlsAbort,
    DnsNxDomain,
    DnsServFail,
    DnsTimeout,
    SocksRejectConnect,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureFaultSpec {
    pub target: FixtureFaultTarget,
    pub outcome: FixtureFaultOutcome,
    pub scope: FixtureFaultScope,
    pub delay_ms: Option<u64>,
}

#[derive(Clone)]
pub struct FaultController {
    inner: Arc<Mutex<Vec<FixtureFaultSpec>>>,
}

impl FaultController {
    fn new() -> Self {
        Self { inner: Arc::new(Mutex::new(Vec::new())) }
    }

    pub fn set(&self, spec: FixtureFaultSpec) {
        if let Ok(mut faults) = self.inner.lock() {
            faults.push(spec);
        }
    }

    pub fn clear(&self) {
        if let Ok(mut faults) = self.inner.lock() {
            faults.clear();
        }
    }

    pub fn snapshot(&self) -> Vec<FixtureFaultSpec> {
        self.inner.lock().map(|faults| faults.clone()).unwrap_or_default()
    }

    fn take_matching<F>(&self, target: FixtureFaultTarget, predicate: F) -> Option<FixtureFaultSpec>
    where
        F: Fn(&FixtureFaultOutcome) -> bool,
    {
        let mut faults = self.inner.lock().ok()?;
        let index = faults.iter().position(|fault| fault.target == target && predicate(&fault.outcome))?;
        let fault = faults[index].clone();
        if fault.scope == FixtureFaultScope::OneShot {
            faults.remove(index);
        }
        Some(fault)
    }
}

#[derive(Clone)]
pub struct EventLog {
    inner: Arc<Mutex<Vec<FixtureEvent>>>,
}

impl EventLog {
    fn new() -> Self {
        Self { inner: Arc::new(Mutex::new(Vec::new())) }
    }

    pub fn record(&self, event: FixtureEvent) {
        if let Ok(mut events) = self.inner.lock() {
            events.push(event);
        }
    }

    pub fn snapshot(&self) -> Vec<FixtureEvent> {
        self.inner.lock().map(|events| events.clone()).unwrap_or_default()
    }

    pub fn clear(&self) {
        if let Ok(mut events) = self.inner.lock() {
            events.clear();
        }
    }
}

pub struct FixtureStack {
    manifest: FixtureManifest,
    events: EventLog,
    faults: FaultController,
    stop: Arc<AtomicBool>,
    handles: Vec<JoinHandle<()>>,
}

impl FixtureStack {
    pub fn start(config: FixtureConfig) -> io::Result<Self> {
        let certificate = generate_simple_self_signed(vec![
            config.fixture_domain.clone(),
            "localhost".to_string(),
            "127.0.0.1".to_string(),
        ])
        .map_err(other_io)?;
        let cert_der = certificate.cert.der().clone();
        let cert_pem = certificate.cert.pem();
        let key_der = PrivateKeyDer::Pkcs8(certificate.key_pair.serialize_der().into());
        let tls_server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der)
                .map_err(other_io)?,
        );

        let stop = Arc::new(AtomicBool::new(false));
        let events = EventLog::new();
        let faults = FaultController::new();

        let manifest = FixtureManifest {
            bind_host: config.bind_host.clone(),
            android_host: "10.0.2.2".to_string(),
            tcp_echo_port: config.tcp_echo_port,
            udp_echo_port: config.udp_echo_port,
            tls_echo_port: config.tls_echo_port,
            dns_udp_port: config.dns_udp_port,
            dns_http_port: config.dns_http_port,
            socks5_port: config.socks5_port,
            control_port: config.control_port,
            fixture_domain: config.fixture_domain.clone(),
            fixture_ipv4: config.fixture_ipv4.clone(),
            dns_answer_ipv4: config.dns_answer_ipv4.clone(),
            tls_certificate_pem: cert_pem,
        };

        let mut handles = Vec::new();
        handles.push(start_tcp_echo_server(
            config.bind_host.clone(),
            config.tcp_echo_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
        )?);
        handles.push(start_udp_echo_server(
            config.bind_host.clone(),
            config.udp_echo_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
        )?);
        handles.push(start_tls_echo_server(
            config.bind_host.clone(),
            config.tls_echo_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            tls_server_config.clone(),
        )?);
        handles.push(start_dns_udp_server(
            config.bind_host.clone(),
            config.dns_udp_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
        )?);
        handles.push(start_dns_http_server(
            config.bind_host.clone(),
            config.dns_http_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
        )?);
        handles.push(start_socks5_server(config.clone(), stop.clone(), events.clone(), faults.clone())?);
        handles.push(start_control_server(
            config.bind_host,
            config.control_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            manifest.clone(),
        )?);

        Ok(Self { manifest, events, faults, stop, handles })
    }

    pub fn manifest(&self) -> &FixtureManifest {
        &self.manifest
    }

    pub fn events(&self) -> EventLog {
        self.events.clone()
    }

    pub fn faults(&self) -> FaultController {
        self.faults.clone()
    }
}

impl Drop for FixtureStack {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        wake_tcp(&self.manifest.bind_host, self.manifest.tcp_echo_port);
        wake_tcp(&self.manifest.bind_host, self.manifest.tls_echo_port);
        wake_tcp(&self.manifest.bind_host, self.manifest.dns_http_port);
        wake_tcp(&self.manifest.bind_host, self.manifest.socks5_port);
        wake_tcp(&self.manifest.bind_host, self.manifest.control_port);
        wake_udp(&self.manifest.bind_host, self.manifest.udp_echo_port);
        wake_udp(&self.manifest.bind_host, self.manifest.dns_udp_port);
        for handle in self.handles.drain(..) {
            let _ = handle.join();
        }
    }
}

impl fmt::Debug for FixtureStack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("FixtureStack").field("manifest", &self.manifest).finish_non_exhaustive()
    }
}

fn start_tcp_echo_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
) -> io::Result<JoinHandle<()>> {
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    Ok(thread::spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut stream, peer)) => {
                    let events = events.clone();
                    let faults = faults.clone();
                    thread::spawn(move || {
                        if let Some(fault) = faults.take_matching(FixtureFaultTarget::TcpEcho, |outcome| {
                            matches!(outcome, FixtureFaultOutcome::TcpReset | FixtureFaultOutcome::TcpTruncate)
                        }) {
                            events.record(event(
                                "tcp_echo",
                                "tcp",
                                peer,
                                stream.local_addr().ok(),
                                &format!("fault:{:?}", fault.outcome),
                                0,
                                None,
                            ));
                            match fault.outcome {
                                FixtureFaultOutcome::TcpReset => {
                                    let _ = stream.shutdown(Shutdown::Both);
                                    return;
                                }
                                FixtureFaultOutcome::TcpTruncate => {
                                    let mut buf = [0u8; 4096];
                                    if let Ok(read) = stream.read(&mut buf) {
                                        let truncated = read.min(4);
                                        let _ = stream.write_all(&buf[..truncated]);
                                        let _ = stream.flush();
                                    }
                                    let _ = stream.shutdown(Shutdown::Both);
                                    return;
                                }
                                _ => {}
                            }
                        }
                        let mut buf = [0u8; 4096];
                        loop {
                            match stream.read(&mut buf) {
                                Ok(0) => return,
                                Ok(read) => {
                                    events.record(event(
                                        "tcp_echo",
                                        "tcp",
                                        peer,
                                        stream.local_addr().ok(),
                                        "echo",
                                        read,
                                        None,
                                    ));
                                    if stream.write_all(&buf[..read]).is_err() {
                                        return;
                                    }
                                }
                                Err(err) if err.kind() == ErrorKind::Interrupted => {}
                                Err(_) => return,
                            }
                        }
                    });
                }
                Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                Err(_) => break,
            }
        }
    }))
}

fn start_udp_echo_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
) -> io::Result<JoinHandle<()>> {
    let socket = UdpSocket::bind((bind_host.as_str(), port))?;
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    let local = socket.local_addr().ok();
    Ok(thread::spawn(move || {
        let mut buf = [0u8; 4096];
        while !stop.load(Ordering::Relaxed) {
            match socket.recv_from(&mut buf) {
                Ok((read, peer)) => {
                    if let Some(fault) = faults.take_matching(FixtureFaultTarget::UdpEcho, |outcome| {
                        matches!(outcome, FixtureFaultOutcome::UdpDrop | FixtureFaultOutcome::UdpDelay)
                    }) {
                        events.record(event(
                            "udp_echo",
                            "udp",
                            peer,
                            local,
                            &format!("fault:{:?}", fault.outcome),
                            read,
                            None,
                        ));
                        match fault.outcome {
                            FixtureFaultOutcome::UdpDrop => continue,
                            FixtureFaultOutcome::UdpDelay => {
                                thread::sleep(Duration::from_millis(fault.delay_ms.unwrap_or(1_500)));
                            }
                            _ => {}
                        }
                    }
                    events.record(event("udp_echo", "udp", peer, local, "echo", read, None));
                    let _ = socket.send_to(&buf[..read], peer);
                }
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                Err(_) => break,
            }
        }
    }))
}

fn start_tls_echo_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    server_config: Arc<ServerConfig>,
) -> io::Result<JoinHandle<()>> {
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    Ok(thread::spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut stream, peer)) => {
                    let config = server_config.clone();
                    let events = events.clone();
                    let faults = faults.clone();
                    thread::spawn(move || {
                        if let Some(_fault) = faults.take_matching(FixtureFaultTarget::TlsEcho, |outcome| {
                            matches!(outcome, FixtureFaultOutcome::TlsAbort)
                        }) {
                            events.record(event(
                                "tls_echo",
                                "tls",
                                peer,
                                stream.local_addr().ok(),
                                "fault:tls_abort",
                                0,
                                None,
                            ));
                            let _ = stream.shutdown(Shutdown::Both);
                            return;
                        }
                        let mut connection = match ServerConnection::new(config) {
                            Ok(connection) => connection,
                            Err(_) => return,
                        };
                        while connection.is_handshaking() {
                            if connection.complete_io(&mut stream).is_err() {
                                return;
                            }
                        }
                        let sni = connection.server_name().map(ToOwned::to_owned);
                        let mut tls = StreamOwned::new(connection, stream);
                        events.record(event("tls_echo", "tls", peer, tls.sock.local_addr().ok(), "handshake", 0, sni));
                        let body = b"fixture tls ok";
                        let response = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                            body.len(),
                            String::from_utf8_lossy(body)
                        );
                        let _ = tls.write_all(response.as_bytes());
                        let _ = tls.flush();
                    });
                }
                Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                Err(_) => break,
            }
        }
    }))
}

fn start_dns_udp_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
) -> io::Result<JoinHandle<()>> {
    let answer_ip =
        Ipv4Addr::from_str(&answer_ip).map_err(|err| io::Error::new(ErrorKind::InvalidInput, err.to_string()))?;
    let socket = UdpSocket::bind((bind_host.as_str(), port))?;
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    let local = socket.local_addr().ok();
    Ok(thread::spawn(move || {
        let mut buf = [0u8; 512];
        while !stop.load(Ordering::Relaxed) {
            match socket.recv_from(&mut buf) {
                Ok((read, peer)) => {
                    let query_name = parse_dns_question_name(&buf[..read]).unwrap_or_else(|| "unknown".to_string());
                    events.record(event("dns_udp", "udp", peer, local, &query_name, read, None));
                    if let Some(fault) = faults.take_matching(FixtureFaultTarget::DnsUdp, |outcome| {
                        matches!(
                            outcome,
                            FixtureFaultOutcome::DnsNxDomain
                                | FixtureFaultOutcome::DnsServFail
                                | FixtureFaultOutcome::DnsTimeout
                        )
                    }) {
                        events.record(event(
                            "dns_udp",
                            "udp",
                            peer,
                            local,
                            &format!("fault:{:?}", fault.outcome),
                            read,
                            None,
                        ));
                        match fault.outcome {
                            FixtureFaultOutcome::DnsTimeout => continue,
                            FixtureFaultOutcome::DnsNxDomain => {
                                if let Ok(response) = build_udp_dns_error_response(&buf[..read], 3) {
                                    let _ = socket.send_to(&response, peer);
                                }
                            }
                            FixtureFaultOutcome::DnsServFail => {
                                if let Ok(response) = build_udp_dns_error_response(&buf[..read], 2) {
                                    let _ = socket.send_to(&response, peer);
                                }
                            }
                            _ => {}
                        }
                    } else if let Ok(response) = build_udp_dns_answer(&buf[..read], answer_ip) {
                        let _ = socket.send_to(&response, peer);
                    }
                }
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                Err(_) => break,
            }
        }
    }))
}

fn start_dns_http_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
) -> io::Result<JoinHandle<()>> {
    start_http_server(bind_host, port, stop, move |request, peer, local| {
        let path = request.path.clone();
        let query = request.query_param("name").unwrap_or_else(|| "unknown".to_string());
        events.record(event("dns_http", "http", peer, local, &format!("{path}?name={query}"), request.raw.len(), None));
        if let Some(fault) = faults.take_matching(FixtureFaultTarget::DnsHttp, |outcome| {
            matches!(
                outcome,
                FixtureFaultOutcome::DnsNxDomain | FixtureFaultOutcome::DnsServFail | FixtureFaultOutcome::DnsTimeout
            )
        }) {
            events.record(event(
                "dns_http",
                "http",
                peer,
                local,
                &format!("fault:{:?}", fault.outcome),
                request.raw.len(),
                None,
            ));
            return match fault.outcome {
                FixtureFaultOutcome::DnsTimeout => {
                    thread::sleep(Duration::from_millis(fault.delay_ms.unwrap_or(1_500)));
                    HttpResponse::json("{}".to_string())
                }
                FixtureFaultOutcome::DnsNxDomain => HttpResponse::json(r#"{"Status":3,"Answer":[]}"#.to_string()),
                FixtureFaultOutcome::DnsServFail => HttpResponse::json(r#"{"Status":2,"Answer":[]}"#.to_string()),
                _ => HttpResponse::not_found(),
            };
        }
        let body = format!(r#"{{"Answer":[{{"type":1,"data":"{answer_ip}"}}]}}"#);
        HttpResponse::json(body)
    })
}

fn start_control_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    manifest: FixtureManifest,
) -> io::Result<JoinHandle<()>> {
    start_http_server(bind_host, port, stop, move |request, peer, local| {
        match (request.method.as_str(), request.path.as_str()) {
            ("GET", "/health") => HttpResponse::text("ok"),
            ("GET", "/manifest") => {
                events.record(event("control", "http", peer, local, "manifest", request.raw.len(), None));
                HttpResponse::json(serde_json::to_string(&manifest).unwrap_or_else(|_| "{}".to_string()))
            }
            ("GET", "/events") => {
                events.record(event("control", "http", peer, local, "events", request.raw.len(), None));
                HttpResponse::json(serde_json::to_string(&events.snapshot()).unwrap_or_else(|_| "[]".to_string()))
            }
            ("POST", "/events/reset") => {
                events.clear();
                HttpResponse::text("reset")
            }
            ("GET", "/faults") => {
                events.record(event("control", "http", peer, local, "faults", request.raw.len(), None));
                HttpResponse::json(serde_json::to_string(&faults.snapshot()).unwrap_or_else(|_| "[]".to_string()))
            }
            ("POST", "/faults") => match serde_json::from_slice::<FixtureFaultSpec>(&request.body) {
                Ok(spec) => {
                    faults.set(spec.clone());
                    events.record(event(
                        "control",
                        "http",
                        peer,
                        local,
                        &format!("fault:set:{:?}:{:?}", spec.target, spec.outcome),
                        request.raw.len(),
                        None,
                    ));
                    HttpResponse::text("ok")
                }
                Err(err) => HttpResponse::bad_request(&err.to_string()),
            },
            ("POST", "/faults/reset") => {
                faults.clear();
                events.record(event("control", "http", peer, local, "faults:reset", request.raw.len(), None));
                HttpResponse::text("reset")
            }
            _ => HttpResponse::not_found(),
        }
    })
}

fn start_http_server<F>(bind_host: String, port: u16, stop: Arc<AtomicBool>, handler: F) -> io::Result<JoinHandle<()>>
where
    F: Fn(HttpRequest, SocketAddr, Option<SocketAddr>) -> HttpResponse + Send + Sync + 'static,
{
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    let handler = Arc::new(handler);
    Ok(thread::spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut stream, peer)) => {
                    let handler = handler.clone();
                    thread::spawn(move || {
                        let local = stream.local_addr().ok();
                        let request = parse_http_request(&mut stream);
                        let response = request
                            .map(|request| handler(request, peer, local))
                            .unwrap_or_else(|_| HttpResponse::not_found());
                        let _ = stream.write_all(&response.to_bytes());
                        let _ = stream.flush();
                        let _ = stream.shutdown(Shutdown::Both);
                    });
                }
                Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                Err(_) => break,
            }
        }
    }))
}

fn start_socks5_server(
    config: FixtureConfig,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
) -> io::Result<JoinHandle<()>> {
    let listener = TcpListener::bind((config.bind_host.as_str(), config.socks5_port))?;
    listener.set_nonblocking(true)?;
    let udp_socket = UdpSocket::bind((config.bind_host.as_str(), 0))?;
    udp_socket.set_read_timeout(Some(IO_TIMEOUT))?;
    let udp_local = udp_socket.local_addr().ok();
    let udp_shared = Arc::new(udp_socket);
    Ok(thread::spawn(move || {
        let udp_worker = {
            let udp_socket = udp_shared.clone();
            let stop = stop.clone();
            let events = events.clone();
            let config = config.clone();
            thread::spawn(move || {
                let mut frame = [0u8; 65535];
                while !stop.load(Ordering::Relaxed) {
                    match udp_socket.recv_from(&mut frame) {
                        Ok((size, peer)) => {
                            if let Ok((destination, payload)) = decode_socks5_udp_frame(&frame[..size]) {
                                let mapped = map_socket_addr(destination, &config);
                                events.record(event(
                                    "socks5_relay",
                                    "udp",
                                    peer,
                                    udp_local,
                                    &mapped.to_string(),
                                    payload.len(),
                                    None,
                                ));
                                if let Ok(forward) = UdpSocket::bind((config.bind_host.as_str(), 0)) {
                                    let _ = forward.set_read_timeout(Some(SOCKS_IO_TIMEOUT));
                                    let _ = forward.send_to(&payload, mapped);
                                    let mut response = [0u8; 4096];
                                    if let Ok((read, from)) = forward.recv_from(&mut response) {
                                        let reply = encode_socks5_udp_frame(from, &response[..read]);
                                        let _ = udp_socket.send_to(&reply, peer);
                                    }
                                }
                            }
                        }
                        Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                        Err(_) => break,
                    }
                }
            })
        };

        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut stream, peer)) => {
                    let config = config.clone();
                    let events = events.clone();
                    let udp_shared = udp_shared.clone();
                    let faults = faults.clone();
                    thread::spawn(move || {
                        let local = stream.local_addr().ok();
                        let _ = stream.set_read_timeout(Some(SOCKS_IO_TIMEOUT));
                        let _ = stream.set_write_timeout(Some(SOCKS_IO_TIMEOUT));
                        if read_socks_greeting(&mut stream).is_err() {
                            return;
                        }
                        if stream.write_all(&[0x05, 0x00]).is_err() {
                            return;
                        }

                        let mut header = [0u8; 4];
                        if stream.read_exact(&mut header).is_err() {
                            return;
                        }

                        match header[1] {
                            0x01 => {
                                let target = match read_socks_target(&mut stream, header[3]) {
                                    Ok(target) => target,
                                    Err(_) => return,
                                };
                                let mapped =
                                    map_target(target, &config).and_then(|target| resolve_socket_addr(&target));
                                let Some(mapped) = mapped.ok() else {
                                    let _ = stream.write_all(&encode_socks_reply_failure());
                                    return;
                                };
                                if let Some(_fault) = faults.take_matching(FixtureFaultTarget::Socks5Relay, |outcome| {
                                    matches!(outcome, FixtureFaultOutcome::SocksRejectConnect)
                                }) {
                                    events.record(event(
                                        "socks5_relay",
                                        "tcp",
                                        peer,
                                        local,
                                        &format!("fault:{}", mapped),
                                        0,
                                        None,
                                    ));
                                    let _ = stream.write_all(&encode_socks_reply_failure());
                                    return;
                                }
                                events.record(event("socks5_relay", "tcp", peer, local, &mapped.to_string(), 0, None));
                                match TcpStream::connect_timeout(&mapped, SOCKS_IO_TIMEOUT) {
                                    Ok(upstream) => {
                                        let reply_addr = upstream.local_addr().unwrap_or(mapped);
                                        let _ = stream.write_all(&encode_socks_reply(reply_addr));
                                        relay_bidirectional(stream, upstream);
                                    }
                                    Err(_) => {
                                        let _ = stream.write_all(&encode_socks_reply_failure());
                                    }
                                }
                            }
                            0x03 => {
                                if consume_socks_addr(&mut stream, header[3]).is_err() {
                                    return;
                                }
                                events.record(event("socks5_relay", "udp", peer, local, "udp_associate", 0, None));
                                if let Some(udp_local) = udp_shared.local_addr().ok() {
                                    let _ = stream.write_all(&encode_socks_reply(udp_local));
                                }
                                let mut buf = [0u8; 16];
                                loop {
                                    match stream.read(&mut buf) {
                                        Ok(0) => break,
                                        Ok(_) => {}
                                        Err(err)
                                            if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                                        Err(_) => break,
                                    }
                                    thread::sleep(IO_POLL_DELAY);
                                }
                            }
                            _ => {
                                let _ = stream.write_all(&encode_socks_reply_failure());
                            }
                        }
                    });
                }
                Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                Err(_) => break,
            }
        }

        let _ = udp_worker.join();
    }))
}

#[derive(Debug, Clone)]
enum SocksTarget {
    Socket(SocketAddr),
    Domain(String, u16),
}

fn read_socks_greeting(stream: &mut TcpStream) -> io::Result<()> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header)?;
    let methods_len = header[1] as usize;
    let mut methods = vec![0u8; methods_len];
    stream.read_exact(&mut methods)?;
    Ok(())
}

fn consume_socks_addr(stream: &mut TcpStream, atyp: u8) -> io::Result<()> {
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
        _ => Err(io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
    }
}

fn read_socks_target(stream: &mut TcpStream, atyp: u8) -> io::Result<SocksTarget> {
    match atyp {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr)?;
            stream.read_exact(&mut port)?;
            Ok(SocksTarget::Socket(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port))))
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            let mut domain = vec![0u8; len[0] as usize];
            let mut port = [0u8; 2];
            stream.read_exact(&mut domain)?;
            stream.read_exact(&mut port)?;
            Ok(SocksTarget::Domain(String::from_utf8_lossy(&domain).to_string(), u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr)?;
            stream.read_exact(&mut port)?;
            Ok(SocksTarget::Socket(SocketAddr::new(IpAddr::from(addr), u16::from_be_bytes(port))))
        }
        _ => Err(io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
    }
}

fn map_target(target: SocksTarget, config: &FixtureConfig) -> io::Result<SocksTarget> {
    Ok(match target {
        SocksTarget::Socket(address) => SocksTarget::Socket(map_socket_addr(address, config)),
        SocksTarget::Domain(domain, port) => {
            if domain == config.fixture_domain {
                SocksTarget::Socket(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port))
            } else {
                SocksTarget::Domain(domain, port)
            }
        }
    })
}

fn resolve_socket_addr(target: &SocksTarget) -> io::Result<SocketAddr> {
    match target {
        SocksTarget::Socket(address) => Ok(*address),
        SocksTarget::Domain(domain, port) => (domain.as_str(), *port)
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| io::Error::new(ErrorKind::AddrNotAvailable, "no target addr")),
    }
}

fn map_socket_addr(address: SocketAddr, config: &FixtureConfig) -> SocketAddr {
    let fixture_ip =
        config.fixture_ipv4.parse::<IpAddr>().unwrap_or_else(|_| IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)));
    if address.ip() == fixture_ip {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), address.port())
    } else {
        address
    }
}

fn encode_socks_reply(address: SocketAddr) -> Vec<u8> {
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

fn encode_socks_reply_failure() -> Vec<u8> {
    vec![0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]
}

fn relay_bidirectional(client: TcpStream, upstream: TcpStream) {
    let mut client_reader = match client.try_clone() {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let mut client_writer = client;
    let mut upstream_reader = match upstream.try_clone() {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let mut upstream_writer = upstream;
    let to_upstream = thread::spawn(move || {
        let _ = io::copy(&mut client_reader, &mut upstream_writer);
    });
    let _ = io::copy(&mut upstream_reader, &mut client_writer);
    let _ = to_upstream.join();
}

fn encode_socks5_udp_frame(destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]);
    match destination {
        SocketAddr::V4(addr) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), io::Error> {
    if frame.len() < 10 {
        return Err(io::Error::new(ErrorKind::InvalidData, "SOCKS5 UDP frame too short"));
    }
    match frame[3] {
        0x01 => Ok((
            SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7])),
                u16::from_be_bytes([frame[8], frame[9]]),
            ),
            frame[10..].to_vec(),
        )),
        0x04 => {
            if frame.len() < 22 {
                return Err(io::Error::new(ErrorKind::InvalidData, "SOCKS5 UDP IPv6 frame too short"));
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            Ok((SocketAddr::new(IpAddr::from(raw), u16::from_be_bytes([frame[20], frame[21]])), frame[22..].to_vec()))
        }
        atyp => Err(io::Error::new(ErrorKind::InvalidData, format!("SOCKS5 UDP atyp unsupported: {atyp}"))),
    }
}

#[derive(Debug)]
struct HttpRequest {
    method: String,
    path: String,
    query: String,
    body: Vec<u8>,
    raw: Vec<u8>,
}

impl HttpRequest {
    fn query_param(&self, key: &str) -> Option<String> {
        self.query.split('&').find_map(|entry| {
            let (name, value) = entry.split_once('=')?;
            (name == key).then(|| percent_decode(value))
        })
    }
}

#[derive(Debug)]
struct HttpResponse {
    status_line: &'static str,
    content_type: &'static str,
    body: Vec<u8>,
}

impl HttpResponse {
    fn json(body: String) -> Self {
        Self { status_line: "HTTP/1.1 200 OK", content_type: "application/json", body: body.into_bytes() }
    }

    fn text(body: &str) -> Self {
        Self {
            status_line: "HTTP/1.1 200 OK",
            content_type: "text/plain; charset=utf-8",
            body: body.as_bytes().to_vec(),
        }
    }

    fn not_found() -> Self {
        Self {
            status_line: "HTTP/1.1 404 Not Found",
            content_type: "text/plain; charset=utf-8",
            body: b"not found".to_vec(),
        }
    }

    fn bad_request(body: &str) -> Self {
        Self {
            status_line: "HTTP/1.1 400 Bad Request",
            content_type: "text/plain; charset=utf-8",
            body: body.as_bytes().to_vec(),
        }
    }

    fn to_bytes(&self) -> Vec<u8> {
        let headers = format!(
            "{}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            self.status_line,
            self.content_type,
            self.body.len()
        );
        let mut bytes = headers.into_bytes();
        bytes.extend_from_slice(&self.body);
        bytes
    }
}

fn parse_http_request(stream: &mut TcpStream) -> io::Result<HttpRequest> {
    let mut raw = read_until_marker(stream, b"\r\n\r\n");
    let request = String::from_utf8_lossy(&raw).into_owned();
    let first_line = request.lines().next().ok_or_else(|| io::Error::new(ErrorKind::InvalidData, "empty request"))?;
    let mut parts = first_line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_string();
    let target = parts.next().unwrap_or("/");
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let content_length = request
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
        })
        .unwrap_or(0);
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        stream.read_exact(&mut body)?;
        raw.extend_from_slice(&body);
    }
    Ok(HttpRequest { method, path: path.to_string(), query: query.to_string(), body, raw })
}

fn read_until_marker(stream: &mut impl Read, marker: &[u8]) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 1];
    while !buf.windows(marker.len()).any(|window| window == marker) {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => buf.extend_from_slice(&chunk[..read]),
            Err(_) => break,
        }
    }
    buf
}

fn build_udp_dns_answer(request: &[u8], answer_ip: Ipv4Addr) -> Result<Vec<u8>, String> {
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

fn build_udp_dns_error_response(request: &[u8], rcode: u16) -> Result<Vec<u8>, String> {
    if request.len() < 12 {
        return Err("short request".to_string());
    }
    let mut answer = Vec::new();
    answer.extend(&request[0..2]);
    answer.extend((0x8180u16 | (rcode & 0x000f)).to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(&request[12..]);
    Ok(answer)
}

fn parse_dns_question_name(request: &[u8]) -> Option<String> {
    let mut cursor = 12usize;
    let mut labels = Vec::new();
    while cursor < request.len() {
        let len = request[cursor] as usize;
        cursor += 1;
        if len == 0 {
            break;
        }
        if cursor + len > request.len() {
            return None;
        }
        labels.push(String::from_utf8_lossy(&request[cursor..cursor + len]).to_string());
        cursor += len;
    }
    (!labels.is_empty()).then(|| labels.join("."))
}

fn event(
    service: &str,
    protocol: &str,
    peer: SocketAddr,
    target: Option<SocketAddr>,
    detail: &str,
    bytes: usize,
    sni: Option<String>,
) -> FixtureEvent {
    FixtureEvent {
        service: service.to_string(),
        protocol: protocol.to_string(),
        peer: peer.to_string(),
        target: target.map_or_else(|| "unknown".to_string(), |addr| addr.to_string()),
        detail: detail.to_string(),
        bytes,
        sni,
        created_at: now_ms(),
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis().try_into().unwrap_or(u64::MAX)
}

fn wake_tcp(host: &str, port: u16) {
    let _ = TcpStream::connect((host, port));
}

fn wake_udp(host: &str, port: u16) {
    if let Ok(socket) = UdpSocket::bind(("127.0.0.1", 0)) {
        let _ = socket.send_to(b"wake", (host, port));
    }
}

fn env_string(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn env_u16(key: &str, default: u16) -> u16 {
    std::env::var(key).ok().and_then(|value| value.parse::<u16>().ok()).unwrap_or(default)
}

fn percent_decode(value: &str) -> String {
    value.replace("%2E", ".").replace("%2e", ".").replace("%3A", ":").replace("%3a", ":").replace('+', " ")
}

fn other_io<E>(error: E) -> io::Error
where
    E: ToString,
{
    io::Error::new(ErrorKind::Other, error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixture_config_reads_env_override() {
        std::env::set_var("RIPDPI_FIXTURE_TCP_ECHO_PORT", "47001");
        let config = FixtureConfig::from_env();
        assert_eq!(config.tcp_echo_port, 47001);
        std::env::remove_var("RIPDPI_FIXTURE_TCP_ECHO_PORT");
    }

    #[test]
    fn manifest_serializes_and_control_url_uses_requested_host() {
        let manifest = FixtureManifest {
            bind_host: "127.0.0.1".to_string(),
            android_host: "10.0.2.2".to_string(),
            tcp_echo_port: 1,
            udp_echo_port: 2,
            tls_echo_port: 3,
            dns_udp_port: 4,
            dns_http_port: 5,
            socks5_port: 6,
            control_port: 7,
            fixture_domain: "fixture.test".to_string(),
            fixture_ipv4: "198.18.0.10".to_string(),
            dns_answer_ipv4: "198.18.0.10".to_string(),
            tls_certificate_pem: "pem".to_string(),
        };

        assert_eq!(manifest.control_url_for_host("10.0.2.2"), "http://10.0.2.2:7");
        let json = serde_json::to_string(&manifest).expect("serialize manifest");
        assert!(json.contains("fixture.test"));
    }
}
