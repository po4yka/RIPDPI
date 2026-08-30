use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use boring::ssl::{SslConnector, SslStream, SslVerifyMode};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tungstenite::WebSocket;

use crate::CloudflareWorkerRoute;
use crate::dc::{TelegramDc, ws_host, ws_url};
use crate::protect;
use crate::transport::WsTransportConfig;

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";

/// A connected WebSocket tunnel to a Telegram DC (BoringSSL TLS backend).
///
/// TLS is handled by BoringSSL (via `ripdpi-tls-profiles`) to produce a
/// ClientHello indistinguishable from Chrome, defeating JA3/JA4 fingerprinting.
pub type WsStream = WebSocket<SslStream<TcpStream>>;

/// Read timeout on the WebSocket's underlying TCP socket. The relay now owns
/// the WebSocket on one thread and uses this timeout as its I/O poll cadence
/// so outbound frames are scheduled promptly without busy-spinning.
pub(crate) const WS_READ_TIMEOUT: Duration = Duration::from_millis(10);

fn resolve_ws_target_with(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    worker_route: Option<&CloudflareWorkerRoute>,
    mut resolve_socket_addrs: impl FnMut(&str) -> io::Result<SocketAddr>,
) -> io::Result<(String, SocketAddr)> {
    let host = match worker_route {
        Some(route) => route.host().to_string(),
        None => ws_host(dc).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
            )
        })?,
    };
    let port = worker_route.map_or(443, CloudflareWorkerRoute::port);
    let target = match resolved_addr {
        Some(target) => target,
        None => resolve_socket_addrs(&format!("{host}:{port}"))?,
    };
    Ok((host, target))
}

fn ensure_tunnelable_dc(dc: TelegramDc) -> io::Result<()> {
    ws_url(dc).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
        )
    })?;
    Ok(())
}

fn resolve_ws_target(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    worker_route: Option<&CloudflareWorkerRoute>,
) -> io::Result<(String, SocketAddr)> {
    resolve_ws_target_with(dc, resolved_addr, worker_route, |addr| {
        addr.to_socket_addrs()?.next().ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, format!("WS tunnel resolved no address: {addr}"))
        })
    })
}

fn connect_tcp_socket_with(
    target: SocketAddr,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
    mut protect_socket: impl FnMut(&Socket, &str) -> io::Result<()>,
) -> io::Result<TcpStream> {
    connect_tcp_socket_with_impl(
        target,
        protect_path,
        connect_timeout,
        &mut protect_socket,
        |socket, target, timeout| match timeout {
            Some(timeout) => socket.connect_timeout(&SockAddr::from(target), timeout),
            None => socket.connect(&SockAddr::from(target)),
        },
    )
}

fn connect_tcp_socket_with_impl(
    target: SocketAddr,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
    mut protect_socket: impl FnMut(&Socket, &str) -> io::Result<()>,
    mut connect_socket: impl FnMut(&Socket, SocketAddr, Option<Duration>) -> io::Result<()>,
) -> io::Result<TcpStream> {
    let domain = match target {
        std::net::SocketAddr::V4(_) => Domain::IPV4,
        std::net::SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;

    match protect_path {
        Some(path) => protect_socket(&socket, path)?,
        // No UDS protect path configured: fall back to the in-process
        // VpnService.protect callback registry when the VPN is up, so the
        // WS-tunnel TCP socket is not captured by the TUN. No-op (and
        // unprotected) when no callback is registered (desktop / VPN down);
        // loopback never needs protection. (UDS-first here, unlike
        // runtime-platform's callback-first vpn_protect — both reach
        // VpnService.protect; keep the orderings as-is.)
        None => protect::protect_via_callback_if_active(&socket, target)?,
    }
    connect_socket(&socket, target, connect_timeout)
        .map_err(|e| io::Error::new(e.kind(), format!("WS tunnel TCP connect to {target}: {e}")))?;
    let tcp: TcpStream = socket.into();
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

fn connect_tcp_socket(
    target: SocketAddr,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    connect_tcp_socket_with(target, protect_path, connect_timeout, protect::protect_socket)
}

fn configure_bootstrap_socket(tcp: &TcpStream, connect_timeout: Option<Duration>) -> io::Result<()> {
    tcp.set_read_timeout(connect_timeout)?;
    tcp.set_write_timeout(connect_timeout)?;
    Ok(())
}

fn configure_relay_socket(tcp: &TcpStream) -> io::Result<()> {
    // The relay polls reads on a short cadence so queued outbound frames do not
    // wait indefinitely behind an idle downlink.
    tcp.set_read_timeout(Some(WS_READ_TIMEOUT))?;
    tcp.set_write_timeout(None)?;
    Ok(())
}

fn configure_established_ws_stream(ws: &mut WsStream) -> io::Result<()> {
    configure_relay_socket(ws.get_mut().get_ref())
}

fn build_tls_connector(
    fake_sni: Option<&str>,
    worker_route: Option<&CloudflareWorkerRoute>,
) -> io::Result<SslConnector> {
    let mut builder = ripdpi_tls_profiles::configure_builder("chrome_stable")
        .map_err(|e| io::Error::other(format!("TLS profile: {e}")))?;
    if worker_route.is_some() {
        builder.set_alpn_protos(HTTP11_ALPN).map_err(|e| io::Error::other(format!("TLS ALPN: {e}")))?;
    }
    if fake_sni.is_some() {
        builder.set_verify(SslVerifyMode::NONE);
    }
    Ok(builder.build())
}

/// Build the Telegram WS upgrade request via the generic composable
/// transport. The Telegram path is now just another consumer of
/// [`crate::transport`]: it supplies a `host` + `/apiws` path and the
/// shared builder applies the `binary` subprotocol. `tokio-tungstenite`
/// 0.27 and the sync `tungstenite` 0.29 share the same `http` 1.x
/// `Request` type, so the generic builder's output feeds the sync
/// `tungstenite::client` call site directly with no conversion.
fn build_ws_request(host: &str) -> io::Result<tungstenite::http::Request<()>> {
    let config = WsTransportConfig::new(host, "/apiws");
    crate::transport::build_ws_request(&config, true).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
}

fn build_ws_request_for_route(
    dc: TelegramDc,
    worker_route: Option<&CloudflareWorkerRoute>,
) -> io::Result<tungstenite::http::Request<()>> {
    ensure_tunnelable_dc(dc)?;
    match worker_route {
        Some(route) => {
            let upstream = ws_url(dc).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
                )
            })?;
            let config = WsTransportConfig::new(route.request_authority(), route.request_path())
                .with_header("Authorization", format!("Bearer {}", route.bearer().expose_secret()))
                .with_header("X-Ripdpi-Upstream", upstream);
            crate::transport::build_ws_request(&config, true)
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
        }
        None => {
            let host = ws_host(dc).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
                )
            })?;
            build_ws_request(&host)
        }
    }
}

/// Open a WebSocket tunnel to the given Telegram DC.
///
/// Uses BoringSSL via `ripdpi-tls-profiles` for TLS, producing a Chrome-compatible
/// JA3/JA4 fingerprint that is indistinguishable from real Chrome traffic to DPI
/// systems.
///
/// If `protect_path` is provided, the underlying TCP socket is protected from
/// Android VPN routing loops before connecting.
pub(crate) fn open_ws_tunnel_with_timeout(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
    fake_sni: Option<&str>,
    worker_route: Option<&CloudflareWorkerRoute>,
) -> io::Result<WsStream> {
    open_ws_tunnel_with_timeout_and_connector(
        dc,
        resolved_addr,
        protect_path,
        connect_timeout,
        fake_sni,
        worker_route,
        build_tls_connector,
    )
}

fn open_ws_tunnel_with_timeout_and_connector(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
    fake_sni: Option<&str>,
    worker_route: Option<&CloudflareWorkerRoute>,
    build_connector: impl FnOnce(Option<&str>, Option<&CloudflareWorkerRoute>) -> io::Result<SslConnector>,
) -> io::Result<WsStream> {
    // Validate the DC is tunnelable before doing any network work; the
    // generic transport request is built from `host` + `/apiws` below.
    ensure_tunnelable_dc(dc)?;
    if worker_route.is_some() && fake_sni.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "Cloudflare Worker WS tunnel route requires verified Worker TLS and cannot be combined with fake_sni",
        ));
    }
    let (host, target) = resolve_ws_target(dc, resolved_addr, worker_route)?;
    let tls_host = fake_sni.unwrap_or(&host);
    let tcp = connect_tcp_socket(target, protect_path, connect_timeout)?;
    configure_bootstrap_socket(&tcp, connect_timeout)?;

    // BoringSSL TLS handshake -- produces Chrome-native cipher suite ordering,
    // GREASE values, and extension layout for DPI fingerprint evasion.
    let connector = build_connector(fake_sni, worker_route)?;
    let tls_stream = connector
        .connect(tls_host, tcp)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("boring TLS: {e}")))?;

    let request = build_ws_request_for_route(dc, worker_route)?;

    // WebSocket handshake over the pre-established BoringSSL stream.
    let (mut ws, _response) = tungstenite::client(request, tls_stream)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {e}")))?;
    configure_established_ws_stream(&mut ws)?;

    Ok(ws)
}

pub fn open_ws_tunnel(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
) -> io::Result<WsStream> {
    open_ws_tunnel_with_timeout(dc, resolved_addr, protect_path, None, None, None)
}

#[cfg(test)]
mod tests {
    use super::*;

    use boring::pkey::{PKey, Private};
    use boring::ssl::{self, SslAcceptor, SslMethod, SslVerifyMode};
    use boring::x509::X509;
    use bytes::Bytes;
    use rcgen::generate_simple_self_signed;
    use std::cell::Cell;
    use std::io::Read;
    use std::net::{Ipv4Addr, TcpListener};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;
    use std::thread;

    #[test]
    fn build_ws_request_includes_binary_subprotocol() {
        // The Telegram path now builds its request through the generic
        // `transport` module: host + `/apiws` -> `wss://.../apiws` with the
        // `binary` subprotocol applied by the shared builder.
        let request = build_ws_request("kws2.web.telegram.org").expect("build request");

        assert_eq!(request.uri().to_string(), "wss://kws2.web.telegram.org/apiws");
        assert_eq!(
            request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
            Some("binary"),
        );
        assert_eq!(request.headers().get("Host").and_then(|value| value.to_str().ok()), Some("kws2.web.telegram.org"),);
    }

    #[test]
    fn worker_route_request_targets_worker_and_confines_upstream() {
        let route = CloudflareWorkerRoute::parse("https://edge.example.workers.dev/relay", "secret-token")
            .expect("valid worker route");

        let request = build_ws_request_for_route(TelegramDc::production(2), Some(&route)).expect("build request");

        assert_eq!(request.uri().to_string(), "wss://edge.example.workers.dev/relay");
        assert_eq!(
            request.headers().get("Host").and_then(|value| value.to_str().ok()),
            Some("edge.example.workers.dev"),
        );
        assert_eq!(
            request.headers().get("Authorization").and_then(|value| value.to_str().ok()),
            Some("Bearer secret-token"),
        );
        assert_eq!(
            request.headers().get("X-Ripdpi-Upstream").and_then(|value| value.to_str().ok()),
            Some("wss://kws2.web.telegram.org/apiws"),
        );
        assert_eq!(
            request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
            Some("binary"),
        );
    }

    #[test]
    fn worker_route_preserves_root_query_in_request_uri() {
        let route = CloudflareWorkerRoute::parse("https://edge.example.workers.dev?tenant=a", "secret-token")
            .expect("valid Worker route");

        let request = build_ws_request_for_route(TelegramDc::production(2), Some(&route)).expect("build request");

        assert_eq!(request.uri().to_string(), "wss://edge.example.workers.dev/?tenant=a");
        assert_eq!(
            request.headers().get("Host").and_then(|value| value.to_str().ok()),
            Some("edge.example.workers.dev")
        );
    }

    #[test]
    fn worker_route_validation_rejects_unsafe_inputs() {
        for (url, bearer) in [
            ("http://edge.example/relay", "secret"),
            ("https://user@edge.example/relay", "secret"),
            ("https://edge.example/relay#frag", "secret"),
            ("https://edge.example/relay\r\nx", "secret"),
            ("https://edge.example/relay path", "secret"),
            ("https://edge.example/%zz", "secret"),
            ("https://edge.example/relay", ""),
            ("https://edge.example/relay", "bad\r\nsecret"),
            ("https://edge.example/relay", "secret token"),
            ("https://edge.example/relay", "秘密"),
        ] {
            let err = CloudflareWorkerRoute::parse(url, bearer).expect_err("route should be rejected");
            assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        }
    }

    #[test]
    fn worker_tls_connector_offers_only_http11_alpn() {
        let route = CloudflareWorkerRoute::parse("https://edge.example.workers.dev/relay", "secret-token")
            .expect("valid worker route");
        let selected = selected_alpn_from_connector(
            build_tls_connector(Some("edge.example.workers.dev"), Some(&route)).expect("worker TLS connector"),
            "edge.example.workers.dev",
        );

        assert_eq!(selected.as_deref(), Some("http/1.1"));
    }

    #[test]
    fn direct_tls_connector_keeps_profile_alpn() {
        let selected = selected_alpn_from_connector(
            build_tls_connector(Some("cover.example"), None).expect("direct TLS connector"),
            "cover.example",
        );

        assert_eq!(selected.as_deref(), Some("h2"));
    }

    #[test]
    fn worker_route_opens_verified_tls_http11_websocket_and_roundtrips_binary() {
        let fixture = spawn_worker_tls_ws_echo_server();
        let worker_url = format!("https://edge.example.workers.dev:{}/relay", fixture.addr.port());
        let route = CloudflareWorkerRoute::parse(worker_url, "secret-token").expect("valid worker route");

        let mut ws = open_ws_tunnel_with_timeout_and_connector(
            TelegramDc::production(2),
            Some(fixture.addr),
            None,
            Some(Duration::from_secs(2)),
            None,
            Some(&route),
            |fake_sni, worker_route| test_tls_connector(&fixture.root_der, fake_sni, worker_route),
        )
        .expect("open worker websocket");
        ws.send(tungstenite::Message::Binary(Bytes::from_static(b"ping"))).expect("send binary");
        let echoed = ws.read().expect("read echo");
        ws.close(None).expect("close websocket");

        assert_eq!(echoed, tungstenite::Message::Binary(Bytes::from_static(b"ping")));
        let observed = fixture.observed.recv().expect("worker observation");
        fixture.handle.join().expect("worker fixture thread");
        assert_eq!(observed.sni.as_deref(), Some("edge.example.workers.dev"));
        assert_eq!(observed.alpn.as_deref(), Some("http/1.1"));
        assert_eq!(observed.uri, "/relay");
        assert_eq!(
            observed.host.as_deref(),
            Some(format!("edge.example.workers.dev:{}", fixture.addr.port()).as_str())
        );
        assert_eq!(observed.authorization.as_deref(), Some("Bearer secret-token"));
        assert_eq!(observed.upstream.as_deref(), Some("wss://kws2.web.telegram.org/apiws"));
        assert_eq!(observed.upgrade.as_deref(), Some("websocket"));
        assert!(observed.connection.as_deref().is_some_and(|value| value.eq_ignore_ascii_case("Upgrade")));
        assert_eq!(observed.version.as_deref(), Some("13"));
        assert_eq!(observed.protocol.as_deref(), Some("binary"));
        assert_eq!(observed.payload, b"ping");
    }

    #[test]
    fn connect_tcp_socket_protects_before_connecting() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let (tx, rx) = mpsc::channel();
        let accept_tx = tx.clone();
        let accept_thread = thread::spawn(move || {
            let (_stream, _peer) = listener.accept().expect("accept connection");
            accept_tx.send("accept").expect("record accept");
        });

        let stream = connect_tcp_socket_with(target, Some("/tmp/protect.sock"), None, |_, path| {
            assert_eq!(path, "/tmp/protect.sock");
            tx.send("protect").expect("record protect");
            Ok(())
        })
        .expect("connect socket");

        let events = [rx.recv().expect("first event"), rx.recv().expect("second event")];
        assert_eq!(events, ["protect", "accept"]);
        assert_eq!(stream.read_timeout().expect("read timeout"), None);
        assert_eq!(stream.write_timeout().expect("write timeout"), None);
        assert!(stream.nodelay().expect("nodelay"));

        accept_thread.join().expect("join accept thread");
    }

    #[test]
    fn connect_tcp_socket_skips_protect_when_path_is_absent() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let called = Arc::new(AtomicBool::new(false));
        let called_flag = called.clone();
        let accept_thread = thread::spawn(move || listener.accept().expect("accept connection"));

        let _stream = connect_tcp_socket_with(target, None, None, |_, _| {
            called_flag.store(true, Ordering::SeqCst);
            Ok(())
        })
        .expect("connect socket");

        assert!(!called.load(Ordering::SeqCst));
        accept_thread.join().expect("join accept thread");
    }

    #[test]
    fn resolve_ws_target_uses_injected_addr_without_dns_lookup() {
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));
        let resolver_called = Cell::new(false);

        let (_host, resolved) = resolve_ws_target_with(TelegramDc::production(2), Some(target), None, |_| {
            resolver_called.set(true);
            Ok(target)
        })
        .expect("resolve target");

        assert_eq!(resolved, target);
        assert!(!resolver_called.get());
    }

    #[test]
    fn resolve_ws_target_uses_test_gateway_hostname() {
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));

        let (host, resolved) =
            resolve_ws_target_with(TelegramDc::from_raw(10_004).expect("test dc"), Some(target), None, |_| Ok(target))
                .expect("resolve target");

        assert_eq!(host, "kws4-test.web.telegram.org");
        assert_eq!(resolved, target);
    }

    #[test]
    fn resolve_ws_target_rejects_non_tunnelable_dc() {
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));
        let error =
            resolve_ws_target_with(TelegramDc::from_raw(-2).expect("media dc"), Some(target), None, |_| Ok(target))
                .expect_err("media dc should be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    fn selected_alpn_from_connector(connector: boring::ssl::SslConnector, server_name: &str) -> Option<String> {
        let acceptor = alpn_selecting_acceptor();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind TLS ALPN listener");
        let addr = listener.local_addr().expect("TLS ALPN listener addr");
        let handle = thread::spawn(move || {
            let (tcp, _) = listener.accept().expect("accept TLS ALPN client");
            let mut stream = acceptor.accept(tcp).expect("server TLS accept");
            let selected =
                stream.ssl().selected_alpn_protocol().map(|value| String::from_utf8_lossy(value).into_owned());
            let mut drain = [0_u8; 1];
            let _ = stream.read(&mut drain);
            selected
        });

        let tcp = TcpStream::connect(addr).expect("connect TLS ALPN client");
        let stream = connector.connect(server_name, tcp).expect("client TLS connect");
        drop(stream);
        handle.join().expect("TLS ALPN fixture thread")
    }

    fn alpn_selecting_acceptor() -> SslAcceptor {
        let certificate =
            generate_simple_self_signed(vec!["edge.example.workers.dev".to_string(), "cover.example".to_string()])
                .expect("self-signed certificate");
        let cert = X509::from_der(certificate.cert.der().as_ref()).expect("BoringSSL fixture cert");
        let key: PKey<Private> =
            PKey::private_key_from_der(&certificate.signing_key.serialize_der()).expect("BoringSSL fixture key");
        let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).expect("fixture acceptor");
        acceptor.set_certificate(&cert).expect("fixture cert");
        acceptor.set_private_key(&key).expect("fixture key");
        acceptor.set_verify(SslVerifyMode::NONE);
        acceptor.set_alpn_select_callback(|_, client| {
            ssl::select_next_proto(b"\x02h2\x08http/1.1", client).ok_or(ssl::AlpnError::NOACK)
        });
        acceptor.build()
    }

    struct WorkerTlsWsFixture {
        addr: SocketAddr,
        root_der: Vec<u8>,
        observed: mpsc::Receiver<WorkerHandshakeObservation>,
        handle: thread::JoinHandle<()>,
    }

    struct WorkerHandshakeObservation {
        sni: Option<String>,
        alpn: Option<String>,
        uri: String,
        host: Option<String>,
        authorization: Option<String>,
        upstream: Option<String>,
        upgrade: Option<String>,
        connection: Option<String>,
        version: Option<String>,
        protocol: Option<String>,
        payload: Vec<u8>,
    }

    type WorkerRequestSnapshot = (
        String,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
    );

    fn spawn_worker_tls_ws_echo_server() -> WorkerTlsWsFixture {
        let certificate = generate_simple_self_signed(vec!["edge.example.workers.dev".to_string()])
            .expect("self-signed worker certificate");
        let root_der = certificate.cert.der().as_ref().to_vec();
        let cert = X509::from_der(certificate.cert.der().as_ref()).expect("BoringSSL fixture cert");
        let key: PKey<Private> =
            PKey::private_key_from_der(&certificate.signing_key.serialize_der()).expect("BoringSSL fixture key");
        let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).expect("worker fixture acceptor");
        acceptor.set_certificate(&cert).expect("worker fixture cert");
        acceptor.set_private_key(&key).expect("worker fixture key");
        acceptor.set_verify(SslVerifyMode::NONE);
        acceptor.set_alpn_select_callback(|_, client| {
            ssl::select_next_proto(HTTP11_ALPN, client).ok_or(ssl::AlpnError::NOACK)
        });
        let acceptor = acceptor.build();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind worker TLS WS listener");
        let addr = listener.local_addr().expect("worker TLS WS listener addr");
        let (observed_tx, observed) = mpsc::channel();
        let handle = thread::spawn(move || {
            let (tcp, _) = listener.accept().expect("accept worker client");
            let tls = acceptor.accept(tcp).expect("worker TLS accept");
            let sni = tls.ssl().servername(ssl::NameType::HOST_NAME).map(ToOwned::to_owned);
            let alpn = tls.ssl().selected_alpn_protocol().map(|value| String::from_utf8_lossy(value).into_owned());
            let mut request_snapshot = None;
            let mut ws = accept_worker_ws_with_snapshot(tls, &mut request_snapshot);
            let payload = match ws.read().expect("worker read binary") {
                tungstenite::Message::Binary(data) => {
                    ws.send(tungstenite::Message::Binary(data.clone())).expect("worker echo binary");
                    data.to_vec()
                }
                other => panic!("expected binary worker payload, got {other:?}"),
            };
            let (uri, host, authorization, upstream, upgrade, connection, version, protocol) =
                request_snapshot.expect("request snapshot");
            observed_tx
                .send(WorkerHandshakeObservation {
                    sni,
                    alpn,
                    uri,
                    host,
                    authorization,
                    upstream,
                    upgrade,
                    connection,
                    version,
                    protocol,
                    payload,
                })
                .expect("send worker observation");
        });

        WorkerTlsWsFixture { addr, root_der, observed, handle }
    }

    #[allow(
        clippy::result_large_err,
        reason = "tungstenite accept_hdr exposes a large HTTP response error type; this test helper does not return it"
    )]
    fn accept_worker_ws_with_snapshot(
        tls: SslStream<TcpStream>,
        request_snapshot: &mut Option<WorkerRequestSnapshot>,
    ) -> tungstenite::WebSocket<SslStream<TcpStream>> {
        tungstenite::accept_hdr(
            tls,
            |req: &tungstenite::handshake::server::Request, mut response: tungstenite::handshake::server::Response| {
                if let Some(proto) = req.headers().get("Sec-WebSocket-Protocol").cloned() {
                    response.headers_mut().insert("Sec-WebSocket-Protocol", proto);
                }
                *request_snapshot = Some((
                    req.uri().to_string(),
                    header_string(req.headers(), "Host"),
                    header_string(req.headers(), "Authorization"),
                    header_string(req.headers(), "X-Ripdpi-Upstream"),
                    header_string(req.headers(), "Upgrade"),
                    header_string(req.headers(), "Connection"),
                    header_string(req.headers(), "Sec-WebSocket-Version"),
                    header_string(req.headers(), "Sec-WebSocket-Protocol"),
                ));
                Ok(response)
            },
        )
        .expect("worker websocket accept")
    }

    fn test_tls_connector(
        root_der: &[u8],
        fake_sni: Option<&str>,
        worker_route: Option<&CloudflareWorkerRoute>,
    ) -> io::Result<SslConnector> {
        let mut builder = ripdpi_tls_profiles::configure_builder("chrome_stable")
            .map_err(|e| io::Error::other(format!("TLS profile: {e}")))?;
        let root = X509::from_der(root_der).map_err(|e| io::Error::other(format!("test root: {e}")))?;
        builder.cert_store_mut().add_cert(root).map_err(|e| io::Error::other(format!("test root store: {e}")))?;
        if worker_route.is_some() {
            builder.set_alpn_protos(HTTP11_ALPN).map_err(|e| io::Error::other(format!("TLS ALPN: {e}")))?;
        }
        if fake_sni.is_some() {
            builder.set_verify(SslVerifyMode::NONE);
        }
        Ok(builder.build())
    }

    fn header_string(headers: &tungstenite::http::HeaderMap, name: &str) -> Option<String> {
        headers.get(name).and_then(|value| value.to_str().ok()).map(ToOwned::to_owned)
    }

    #[test]
    fn connect_tcp_socket_passes_configured_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let accept_thread = thread::spawn(move || listener.accept().expect("accept connection"));
        let observed_timeout = Cell::new(None);
        let expected_timeout = Duration::from_millis(321);

        let stream = connect_tcp_socket_with_impl(
            target,
            None,
            Some(expected_timeout),
            |_socket, _path| unreachable!("protect should not run"),
            |socket, target, timeout| {
                observed_timeout.set(timeout);
                socket.connect_timeout(&SockAddr::from(target), timeout.expect("connect timeout"))
            },
        )
        .expect("connect socket");

        assert_eq!(observed_timeout.get(), Some(expected_timeout));
        assert!(stream.nodelay().expect("nodelay"));
        accept_thread.join().expect("join accept thread");
    }
}
