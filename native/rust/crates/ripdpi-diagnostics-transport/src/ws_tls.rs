use std::io;
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use rustls::KeyLog;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use tungstenite::WebSocket;
use tungstenite::client::IntoClientRequest;

use crate::transport::TargetAddress;
use crate::transport::resolve_addresses_with_timeout;
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

pub type WsOverTlsStream = WebSocket<StreamOwned<ClientConnection, TcpStream>>;
pub type WsTlsKeyLogCallback = Arc<dyn KeyLog>;

#[derive(Clone, Debug)]
pub struct WsOverTlsTarget {
    pub host: String,
    pub port: u16,
    pub path: String,
    pub resolved_addr: Option<SocketAddr>,
    pub connect_timeout: Option<Duration>,
}

impl WsOverTlsTarget {
    pub fn new(host: impl Into<String>, path: impl Into<String>) -> Self {
        Self { host: host.into(), port: 443, path: path.into(), resolved_addr: None, connect_timeout: None }
    }

    pub fn with_resolved_addr(mut self, resolved_addr: Option<SocketAddr>) -> Self {
        self.resolved_addr = resolved_addr;
        self
    }
}

#[derive(Clone, Debug, Default)]
pub struct WsOverTlsConnector;

impl WsOverTlsConnector {
    pub fn probe(&self, target: &WsOverTlsTarget) -> io::Result<()> {
        self.probe_with_key_log(target, None)
    }

    pub fn probe_with_key_log(&self, target: &WsOverTlsTarget, key_log: Option<WsTlsKeyLogCallback>) -> io::Result<()> {
        let _stream = self.connect_with_key_log(target, key_log)?;
        Ok(())
    }

    pub fn probe_with_key_log_and_abort(
        &self,
        target: &WsOverTlsTarget,
        key_log: Option<WsTlsKeyLogCallback>,
        should_abort: &dyn Fn() -> Option<&'static str>,
    ) -> io::Result<()> {
        let _stream = self.connect_with_key_log_and_abort(target, key_log, should_abort)?;
        Ok(())
    }

    pub fn connect(&self, target: &WsOverTlsTarget) -> io::Result<WsOverTlsStream> {
        self.connect_with_key_log(target, None)
    }

    pub fn connect_with_key_log(
        &self,
        target: &WsOverTlsTarget,
        key_log: Option<WsTlsKeyLogCallback>,
    ) -> io::Result<WsOverTlsStream> {
        self.connect_with_key_log_and_abort(target, key_log, &|| None)
    }

    pub fn connect_with_key_log_and_abort(
        &self,
        target: &WsOverTlsTarget,
        key_log: Option<WsTlsKeyLogCallback>,
        should_abort: &dyn Fn() -> Option<&'static str>,
    ) -> io::Result<WsOverTlsStream> {
        let control =
            WsOverTlsControl::new(should_abort).with_timeout_cap(target.connect_timeout.unwrap_or(IO_TIMEOUT));
        connect_with_steps(target, key_log, &control, &mut ProductionWsHandshakeSteps)
    }
}

struct WsOverTlsControl<'a> {
    should_abort: &'a dyn Fn() -> Option<&'static str>,
    timeout_cap: Duration,
}

impl<'a> WsOverTlsControl<'a> {
    pub fn new(should_abort: &'a dyn Fn() -> Option<&'static str>) -> Self {
        Self { should_abort, timeout_cap: IO_TIMEOUT }
    }

    pub fn with_timeout_cap(mut self, timeout_cap: Duration) -> Self {
        self.timeout_cap = timeout_cap;
        self
    }

    fn ensure_not_aborted(&self) -> io::Result<()> {
        if let Some(reason) = (self.should_abort)() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, format!("probe_aborted:{reason}")));
        }
        Ok(())
    }

    fn remaining_timeout(&self) -> io::Result<Duration> {
        self.ensure_not_aborted()?;
        bounded_scan_io_timeout(self.timeout_cap).map_err(|err| io::Error::new(io::ErrorKind::TimedOut, err))
    }

    fn error_after_blocking_io(&self, original: io::Error) -> io::Error {
        if let Some(reason) = (self.should_abort)() {
            io::Error::new(io::ErrorKind::Interrupted, format!("probe_aborted:{reason}"))
        } else if bounded_scan_io_timeout(self.timeout_cap).is_err() {
            io::Error::new(io::ErrorKind::TimedOut, "scan_deadline_exceeded")
        } else {
            original
        }
    }
}

trait WsHandshakeSteps {
    type Tcp;
    type Tls;
    type Ws;

    fn resolve(&mut self, target: &WsOverTlsTarget, timeout: Duration) -> io::Result<SocketAddr>;
    fn connect_tcp(&mut self, addr: SocketAddr, timeout: Duration) -> io::Result<Self::Tcp>;
    fn connect_tls(
        &mut self,
        tcp: Self::Tcp,
        host: &str,
        key_log: Option<WsTlsKeyLogCallback>,
        timeout: Duration,
        control: &WsOverTlsControl<'_>,
    ) -> io::Result<Self::Tls>;
    fn websocket_handshake(
        &mut self,
        target: &WsOverTlsTarget,
        tls: Self::Tls,
        timeout: Duration,
        control: &WsOverTlsControl<'_>,
    ) -> io::Result<Self::Ws>;
}

fn connect_with_steps<Steps: WsHandshakeSteps>(
    target: &WsOverTlsTarget,
    key_log: Option<WsTlsKeyLogCallback>,
    control: &WsOverTlsControl<'_>,
    steps: &mut Steps,
) -> io::Result<Steps::Ws> {
    let socket_addr =
        steps.resolve(target, control.remaining_timeout()?).map_err(|err| control.error_after_blocking_io(err))?;
    let tcp = steps
        .connect_tcp(socket_addr, control.remaining_timeout()?)
        .map_err(|err| control.error_after_blocking_io(err))?;
    let tls = steps
        .connect_tls(tcp, target.host.as_str(), key_log, control.remaining_timeout()?, control)
        .map_err(|err| control.error_after_blocking_io(err))?;
    steps
        .websocket_handshake(target, tls, control.remaining_timeout()?, control)
        .map_err(|err| control.error_after_blocking_io(err))
}

struct ProductionWsHandshakeSteps;

impl WsHandshakeSteps for ProductionWsHandshakeSteps {
    type Tcp = TcpStream;
    type Tls = StreamOwned<ClientConnection, TcpStream>;
    type Ws = WsOverTlsStream;

    fn resolve(&mut self, target: &WsOverTlsTarget, timeout: Duration) -> io::Result<SocketAddr> {
        resolve_target_addr(target, timeout)
    }

    fn connect_tcp(&mut self, addr: SocketAddr, timeout: Duration) -> io::Result<Self::Tcp> {
        connect_tcp(addr, timeout)
    }

    fn connect_tls(
        &mut self,
        tcp: Self::Tcp,
        host: &str,
        key_log: Option<WsTlsKeyLogCallback>,
        timeout: Duration,
        control: &WsOverTlsControl<'_>,
    ) -> io::Result<Self::Tls> {
        connect_tls(tcp, host, key_log, timeout, control)
    }

    fn websocket_handshake(
        &mut self,
        target: &WsOverTlsTarget,
        tls: Self::Tls,
        timeout: Duration,
        control: &WsOverTlsControl<'_>,
    ) -> io::Result<Self::Ws> {
        websocket_handshake(target, tls, timeout, control)
    }
}

fn resolve_target_addr(target: &WsOverTlsTarget, timeout: Duration) -> io::Result<SocketAddr> {
    if let Some(addr) = target.resolved_addr {
        return Ok(addr);
    }
    resolve_addresses_with_timeout(&TargetAddress::Host(target.host.clone()), target.port, timeout)
        .map_err(|err| io::Error::new(io::ErrorKind::TimedOut, err))?
        .into_iter()
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "WS over TLS target resolved no addresses"))
}

fn connect_tcp(addr: SocketAddr, timeout: Duration) -> io::Result<TcpStream> {
    let socket =
        Socket::new(if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 }, Type::STREAM, Some(Protocol::TCP))?;
    // Protect the fd before connect when the VPN is up, so this Telegram WS
    // probe socket is not captured by the app's own TUN route. The shared
    // transport helper handles loopback-skip, the no-op when no protect
    // callback is registered (desktop / VPN down), and fail-closed when a
    // registered protect fails. DIAG-1.
    crate::transport::protect::protect_for_target(&socket, addr)?;
    socket
        .connect_timeout(&SockAddr::from(addr), timeout)
        .map_err(|err| io::Error::new(err.kind(), format!("WS over TLS TCP connect to {addr}: {err}")))?;
    let stream: TcpStream = socket.into();
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    Ok(stream)
}

fn connect_tls(
    tcp: TcpStream,
    host: &str,
    key_log: Option<WsTlsKeyLogCallback>,
    timeout: Duration,
    control: &WsOverTlsControl<'_>,
) -> io::Result<StreamOwned<ClientConnection, TcpStream>> {
    tcp.set_read_timeout(Some(timeout))?;
    tcp.set_write_timeout(Some(timeout))?;
    let server_name = ServerName::try_from(host.to_string())
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    let connection = ClientConnection::new(default_tls_config(key_log), server_name)
        .map_err(|err| io::Error::new(io::ErrorKind::ConnectionRefused, format!("TLS setup: {err}")))?;
    let mut tls = StreamOwned::new(connection, tcp);
    while tls.conn.is_handshaking() {
        let timeout = control.remaining_timeout()?;
        tls.sock.set_read_timeout(Some(timeout))?;
        tls.sock.set_write_timeout(Some(timeout))?;
        if let Err(err) = tls.conn.complete_io(&mut tls.sock) {
            return Err(control.error_after_blocking_io(io::Error::new(err.kind(), format!("TLS handshake: {err}"))));
        }
    }
    Ok(tls)
}

fn websocket_handshake(
    target: &WsOverTlsTarget,
    tls: StreamOwned<ClientConnection, TcpStream>,
    timeout: Duration,
    control: &WsOverTlsControl<'_>,
) -> io::Result<WsOverTlsStream> {
    control.ensure_not_aborted()?;
    tls.sock.set_read_timeout(Some(timeout))?;
    tls.sock.set_write_timeout(Some(timeout))?;
    let request = build_ws_request(target)?;
    let (ws, _response) = tungstenite::client(request, tls).map_err(|err| {
        control
            .error_after_blocking_io(io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {err}")))
    })?;
    Ok(ws)
}

fn default_tls_config(key_log: Option<WsTlsKeyLogCallback>) -> Arc<ClientConfig> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_root_certificates(roots)
        .with_no_client_auth();
    if let Some(key_log) = key_log {
        config.key_log = key_log;
    }
    Arc::new(config)
}

fn build_ws_request(target: &WsOverTlsTarget) -> io::Result<tungstenite::http::Request<()>> {
    let path = if target.path.starts_with('/') { target.path.clone() } else { format!("/{}", target.path) };
    let url = format!("wss://{}{}", target.host, path);
    let mut request = url.into_client_request().map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err))?;
    request.headers_mut().insert("Sec-WebSocket-Protocol", tungstenite::http::HeaderValue::from_static("binary"));
    Ok(request)
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::os::fd::RawFd;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
    use std::time::Instant;

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;
    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    // The protect callback slot is process-global; serialize the tests that
    // register/clear it so a parallel test cannot observe a half-installed
    // callback.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    struct DenyingCallback {
        called: AtomicBool,
    }

    impl ProtectCallback for DenyingCallback {
        fn protect(&self, _fd: RawFd) -> io::Result<()> {
            self.called.store(true, Ordering::Release);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "protect denied"))
        }
    }

    #[test]
    fn target_defaults_to_standard_wss_port() {
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws");

        assert_eq!(target.port, 443);
        assert_eq!(target.path, "/apiws");
        assert_eq!(target.resolved_addr, None);
    }

    #[test]
    fn target_accepts_pre_resolved_addr() {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 443);
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws").with_resolved_addr(Some(addr));

        assert_eq!(resolve_target_addr(&target, Duration::from_millis(500)).expect("resolved addr"), addr);
    }

    #[test]
    fn ws_request_uses_binary_subprotocol() {
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "apiws");
        let request = build_ws_request(&target).expect("request");

        assert_eq!(request.uri().to_string(), "wss://kws2.web.telegram.org/apiws");
        assert_eq!(
            request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
            Some("binary"),
        );
    }

    #[test]
    fn controlled_handshake_recomputes_timeout_before_each_blocking_step() {
        let mut steps = FakeWsSteps::default();
        let control = WsOverTlsControl::new(&|| None);
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws");

        with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(80)), || {
            connect_with_steps(&target, None, &control, &mut steps).expect("fake handshake");
        });

        assert_eq!(steps.calls, ["resolve", "tcp", "tls", "ws"]);
        assert_eq!(steps.timeouts.len(), 4);
        assert!(
            steps.timeouts[1] < steps.timeouts[0],
            "timeout must be recomputed after DNS rather than reusing a stale value"
        );
    }

    #[test]
    fn controlled_handshake_checks_abort_before_dns_resolution() {
        let mut steps = FakeWsSteps::default();
        let control = WsOverTlsControl::new(&|| Some("cancelled"));
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws");

        let err = connect_with_steps(&target, None, &control, &mut steps).expect_err("abort before DNS");

        assert_eq!(err.kind(), io::ErrorKind::Interrupted);
        assert_eq!(err.to_string(), "probe_aborted:cancelled");
        assert!(steps.calls.is_empty());
    }

    #[test]
    fn controlled_handshake_rejects_tcp_after_dns_consumes_scan_deadline() {
        let mut steps = FakeWsSteps { resolve_delay: Duration::from_millis(30), ..FakeWsSteps::default() };
        let control = WsOverTlsControl::new(&|| None);
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws");

        let err = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(10)), || {
            connect_with_steps(&target, None, &control, &mut steps).expect_err("deadline before TCP")
        });

        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert_eq!(err.to_string(), "scan_deadline_exceeded");
        assert_eq!(steps.calls, ["resolve"]);
    }

    // DIAG-1 regression: pre-fix `connect_tcp` connected without protecting the
    // fd. A registered protect callback must be invoked BEFORE connect, and a
    // protect denial must fail the connect (fail-closed) — not proceed
    // unprotected into the TUN.
    #[test]
    fn non_loopback_connect_attempts_protect_and_fails_closed() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(DenyingCallback { called: AtomicBool::new(false) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 443);
        let err = connect_tcp(target, Duration::from_millis(200)).expect_err("a protect denial must fail the connect");

        assert!(cb.called.load(Ordering::Acquire), "protect must be attempted before connect");
        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied);
        unregister_protect_callback();
    }

    #[test]
    fn loopback_connect_is_not_protected() {
        let _lock = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind loopback listener");
        let addr = listener.local_addr().expect("listener addr");
        let _stream = connect_tcp(addr, Duration::from_millis(500)).expect("loopback connect succeeds");

        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1, "loopback target must not be protected");
        unregister_protect_callback();
    }

    #[derive(Default)]
    struct FakeWsSteps {
        calls: Vec<&'static str>,
        timeouts: Vec<Duration>,
        resolve_delay: Duration,
    }

    impl WsHandshakeSteps for FakeWsSteps {
        type Tcp = ();
        type Tls = ();
        type Ws = ();

        fn resolve(&mut self, _target: &WsOverTlsTarget, timeout: Duration) -> io::Result<SocketAddr> {
            self.calls.push("resolve");
            self.timeouts.push(timeout);
            if !self.resolve_delay.is_zero() {
                std::thread::sleep(self.resolve_delay);
            }
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443))
        }

        fn connect_tcp(&mut self, _addr: SocketAddr, timeout: Duration) -> io::Result<Self::Tcp> {
            self.calls.push("tcp");
            self.timeouts.push(timeout);
            Ok(())
        }

        fn connect_tls(
            &mut self,
            _tcp: Self::Tcp,
            _host: &str,
            _key_log: Option<WsTlsKeyLogCallback>,
            timeout: Duration,
            _control: &WsOverTlsControl<'_>,
        ) -> io::Result<Self::Tls> {
            self.calls.push("tls");
            self.timeouts.push(timeout);
            Ok(())
        }

        fn websocket_handshake(
            &mut self,
            _target: &WsOverTlsTarget,
            _tls: Self::Tls,
            timeout: Duration,
            _control: &WsOverTlsControl<'_>,
        ) -> io::Result<Self::Ws> {
            self.calls.push("ws");
            self.timeouts.push(timeout);
            Ok(())
        }
    }
}
