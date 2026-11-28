use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::*;

static SOCKET_COUNTER: AtomicUsize = AtomicUsize::new(0);
const USERNAME: &str = "ripdpi";
const PASSWORD: &str = "session-secret";

struct SocketPath(PathBuf);

impl SocketPath {
    fn new() -> Self {
        let id = SOCKET_COUNTER.fetch_add(1, Ordering::Relaxed);
        Self(PathBuf::from(format!("/tmp/rdns-{}-{id}.sock", std::process::id())))
    }
}

impl Drop for SocketPath {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn unix_transport(path: &SocketPath, credentials: Option<(&str, &str)>) -> EncryptedDnsTransport {
    EncryptedDnsTransport::Socks5Unix {
        path: EncryptedDnsUnixPath::new(path.0.clone()),
        credentials: credentials.map(|(username, password)| EncryptedDnsSocks5Credentials {
            username: username.to_string(),
            password: password.to_string(),
        }),
    }
}

type AddressTypes = Arc<std::sync::Mutex<Vec<u8>>>;

fn start_unix_socks_proxy(
    path: &SocketPath,
    expected_host: &str,
    target_port: u16,
    connections: usize,
    required_credentials: bool,
) -> (thread::JoinHandle<()>, AddressTypes) {
    let listener = UnixListener::bind(&path.0).expect("proxy listener");
    let expected_host = expected_host.to_string();
    let address_types = AddressTypes::default();
    let observed = address_types.clone();
    let handle = thread::spawn(move || {
        let workers: Vec<_> = (0..connections)
            .map(|_| {
                let (client, _) = listener.accept().expect("proxy accept");
                let (expected_host, observed) = (expected_host.clone(), observed.clone());
                thread::spawn(move || {
                    serve_unix_socks_client(client, &expected_host, target_port, required_credentials, &observed);
                })
            })
            .collect();
        for worker in workers {
            worker.join().expect("proxy worker completes");
        }
    });
    (handle, address_types)
}

fn serve_unix_socks_client(
    mut client: UnixStream,
    expected_host: &str,
    target_port: u16,
    required_credentials: bool,
    address_types: &AddressTypes,
) {
    let mut head = [0u8; 2];
    client.read_exact(&mut head).expect("proxy greeting");
    let mut methods = vec![0u8; usize::from(head[1])];
    client.read_exact(&mut methods).expect("proxy methods");
    if required_credentials {
        assert!(methods.contains(&0x02));
        client.write_all(&[0x05, 0x02]).expect("proxy method reply");
        let mut version_and_len = [0u8; 2];
        client.read_exact(&mut version_and_len).expect("auth header");
        let mut username = vec![0u8; usize::from(version_and_len[1])];
        client.read_exact(&mut username).expect("auth username");
        let mut password_len = [0u8; 1];
        client.read_exact(&mut password_len).expect("auth password length");
        let mut password = vec![0u8; usize::from(password_len[0])];
        client.read_exact(&mut password).expect("auth password");
        if username != USERNAME.as_bytes() || password != PASSWORD.as_bytes() {
            client.write_all(&[0x01, 0x01]).expect("auth failure");
            return;
        }
        client.write_all(&[0x01, 0x00]).expect("auth success");
    } else {
        client.write_all(&[0x05, 0x00]).expect("proxy method reply");
    }
    let mut header = [0u8; 4];
    client.read_exact(&mut header).expect("proxy request header");
    assert_eq!(&header[..3], &[0x05, 0x01, 0x00]);
    address_types.lock().expect("address types").push(header[3]);
    let (host, port) = read_socks_target(&mut client, header[3]);
    assert_eq!(host, expected_host);
    assert_eq!(port, target_port);
    let upstream = TcpStream::connect((Ipv4Addr::LOCALHOST, target_port)).expect("proxy upstream");
    let [p1, p2] = target_port.to_be_bytes();
    client.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p1, p2]).expect("proxy success reply");
    relay_unix_streams(client, upstream);
}

fn relay_unix_streams(client: UnixStream, upstream: TcpStream) {
    let mut client_reader = client.try_clone().expect("client clone");
    let mut client_writer = client;
    let mut upstream_reader = upstream.try_clone().expect("upstream clone");
    let mut upstream_writer = upstream;
    let to_upstream = thread::spawn(move || {
        let _ = std::io::copy(&mut client_reader, &mut upstream_writer);
        let _ = upstream_writer.shutdown(std::net::Shutdown::Both);
    });
    let _ = std::io::copy(&mut upstream_reader, &mut client_writer);
    let _ = client_writer.shutdown(std::net::Shutdown::Write);
    to_upstream.join().expect("relay join");
}

fn tls_server_config(alpn: &[&[u8]]) -> (Arc<ServerConfig>, CertificateDer<'static>) {
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let mut config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der.clone()], key_der)
        .expect("server config");
    config.alpn_protocols = alpn.iter().map(|protocol| protocol.to_vec()).collect();
    (Arc::new(config), certificate_der)
}

fn doh_endpoint(port: u16) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some("fixture".to_string()),
        host: "fixture.test".to_string(),
        port,
        tls_server_name: Some("fixture.test".to_string()),
        bootstrap_ips: Vec::new(),
        doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

fn doh_resolver(
    port: u16,
    transport: EncryptedDnsTransport,
    roots: Vec<CertificateDer<'static>>,
    timeout: Duration,
) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_extra_tls_roots(doh_endpoint(port), transport, timeout, roots).expect("resolver builds")
}

fn doh_server(
    reply: impl FnOnce(TcpStream, Arc<ServerConfig>) + Send + 'static,
) -> (u16, CertificateDer<'static>, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let (config, certificate) = tls_server_config(&[]);
    let handle = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        reply(stream, config);
    });
    (port, certificate, handle)
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, listener threads, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_returns_the_answer_with_credentials() {
    let query = build_query("fixture.test");
    let response = build_response(&query, Ipv4Addr::new(198, 18, 0, 71));
    let (server_query, server_response) = (query.clone(), response.clone());
    let (port, certificate, server) =
        doh_server(move |stream, config| serve_https_doh(stream, config, &server_query, &server_response));
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, true);

    let resolver =
        doh_resolver(port, unix_transport(&socket, Some((USERNAME, PASSWORD))), vec![certificate], DEFAULT_TIMEOUT);
    let answer = resolver.exchange(&query).await.expect("DoH response");

    assert_eq!(extract_ip_answers(&answer).expect("answers"), vec!["198.18.0.71"]);
    proxy.join().expect("proxy completes");
    server.join().expect("server completes");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, listener threads, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_fails_with_wrong_credentials() {
    let query = build_query("fixture.test");
    let (port, certificate, _server) = doh_server(|_, _| {});
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, true);

    let resolver =
        doh_resolver(port, unix_transport(&socket, Some((USERNAME, "wrong"))), vec![certificate], DEFAULT_TIMEOUT);
    let error = resolver.exchange(&query).await.expect_err("wrong credentials must fail");

    assert!(matches!(error, EncryptedDnsError::Socks5(_)), "{error:?}");
    proxy.join().expect("proxy completes");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, listener threads, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_rejects_an_untrusted_certificate() {
    let query = build_query("fixture.test");
    let (port, _certificate, server) = doh_server(|stream, config| {
        let mut tls = StreamOwned::new(ServerConnection::new(config).expect("server connection"), stream);
        let _ = tls.conn.complete_io(&mut tls.sock);
    });
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, false);

    let resolver = doh_resolver(port, unix_transport(&socket, None), Vec::new(), DEFAULT_TIMEOUT);
    let error = resolver.exchange(&query).await.expect_err("an unverified certificate must fail");

    assert!(matches!(error, EncryptedDnsError::Tls(_)), "{error:?}");
    drop(resolver);
    proxy.join().expect("proxy completes");
    server.join().expect("server completes");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, listener threads, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_rejects_a_reply_with_the_wrong_content_type() {
    let query = build_query("fixture.test");
    let body = build_response(&query, Ipv4Addr::new(198, 18, 0, 72));
    let head = format!("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {}\r\n\r\n", body.len());
    let server_query = query.clone();
    let (port, certificate, server) = doh_server(move |stream, config| {
        serve_https_doh_raw(stream, config, &server_query, &[head.into_bytes(), body]);
    });
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, false);

    let resolver = doh_resolver(port, unix_transport(&socket, None), vec![certificate], DEFAULT_TIMEOUT);
    let error = resolver.exchange(&query).await.expect_err("a wrong media type must fail");

    assert!(matches!(&error, EncryptedDnsError::Request(message) if message.contains("Content-Type")), "{error:?}");
    drop(resolver);
    proxy.join().expect("proxy completes");
    server.join().expect("server completes");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, listener thread, and socket path; cancellation drops them.
async fn doh_over_a_stalled_unix_socks_proxy_times_out() {
    let socket = SocketPath::new();
    let listener = UnixListener::bind(&socket.0).expect("proxy listener");
    let proxy = thread::spawn(move || {
        let (mut client, _) = listener.accept().expect("proxy accept");
        let _ = std::io::copy(&mut client, &mut std::io::sink());
    });

    let resolver = doh_resolver(443, unix_transport(&socket, None), Vec::new(), Duration::from_millis(300));
    let error = resolver.exchange(&build_query("fixture.test")).await.expect_err("a stalled proxy must time out");

    assert!(matches!(&error, EncryptedDnsError::Socks5(message) if message.contains("timed out")), "{error:?}");
    drop(resolver);
    proxy.join().expect("proxy completes");
}

fn dot_server(config: Arc<ServerConfig>, query: Vec<u8>, response: Vec<u8>) -> (u16, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let handle = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_dot(stream, config, &query, &response);
    });
    (port, handle)
}

#[test]
fn dot_over_a_unix_socks_route_returns_the_answer() {
    let query = build_query("fixture.test");
    let response = build_response(&query, Ipv4Addr::new(198, 18, 0, 73));
    let (config, certificate) = tls_server_config(&[]);
    let (port, server) = dot_server(config, query.clone(), response);
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, true);
    let mut endpoint = dot_fixture_endpoint(port);
    endpoint.bootstrap_ips.clear();

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        endpoint,
        unix_transport(&socket, Some((USERNAME, PASSWORD))),
        DEFAULT_TIMEOUT,
        vec![certificate],
    )
    .expect("resolver builds");
    let answer = resolver.exchange_blocking(&query).expect("DoT response");

    assert_eq!(extract_ip_answers(&answer).expect("answers"), vec!["198.18.0.73"]);
    drop(resolver);
    proxy.join().expect("proxy completes");
    server.join().expect("server completes");
}

#[test]
fn dot_over_a_unix_socks_route_rejects_an_untrusted_certificate() {
    let query = build_query("fixture.test");
    let (config, _certificate) = tls_server_config(&[]);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        let mut tls = StreamOwned::new(ServerConnection::new(config).expect("server connection"), stream);
        let _ = tls.conn.complete_io(&mut tls.sock);
    });
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, false);
    let mut endpoint = dot_fixture_endpoint(port);
    endpoint.bootstrap_ips.clear();

    let resolver = EncryptedDnsResolver::new(endpoint, unix_transport(&socket, None)).expect("resolver builds");
    let error = resolver.exchange_blocking(&query).expect_err("an unverified certificate must fail");

    assert!(matches!(error, EncryptedDnsError::Tls(_)), "{error:?}");
    drop(resolver);
    proxy.join().expect("proxy completes");
    server.join().expect("server completes");
}

#[test]
fn dnscrypt_over_a_unix_socks_route_returns_the_answer() {
    let query = build_query("fixture.test");
    let server = DnsCryptTestServer::new("resolver.test");
    let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, Ipv4Addr::new(198, 18, 0, 74)));
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "resolver.test", port, 2, true);

    let resolver = EncryptedDnsResolver::new(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: Some("fixture".to_string()),
            host: "resolver.test".to_string(),
            port,
            tls_server_name: None,
            bootstrap_ips: Vec::new(),
            doh_url: None,
            dnscrypt_provider_name: Some(server.provider_name.clone()),
            dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
            odoh: None,
        },
        unix_transport(&socket, Some((USERNAME, PASSWORD))),
    )
    .expect("resolver builds");
    let answer = resolver.exchange_blocking(&query).expect("DNSCrypt response");

    assert_eq!(extract_ip_answers(&answer).expect("answers"), vec!["198.18.0.74"]);
    drop(resolver);
    proxy.join().expect("proxy completes");
    handle.join().expect("server completes");
}

#[test]
fn unix_socks_route_rejects_a_path_the_kernel_cannot_bind() {
    for path in ["relative.sock".to_string(), format!("/{}", "a".repeat(120)), "/tmp/a\0b".to_string()] {
        let transport = EncryptedDnsTransport::Socks5Unix { path: EncryptedDnsUnixPath::new(path), credentials: None };
        let error =
            EncryptedDnsResolver::new(doh_endpoint(443), transport).map(|_| ()).expect_err("path must be rejected");
        assert!(matches!(error, EncryptedDnsError::InvalidEndpoint(_)), "{error:?}");
    }
}

#[test]
fn unix_socks_route_debug_output_hides_the_path() {
    let transport =
        EncryptedDnsTransport::Socks5Unix { path: EncryptedDnsUnixPath::new("/data/px-secret"), credentials: None };

    assert!(!format!("{transport:?}").contains("px-secret"));
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver and returns before any await point is left pending.
async fn doq_over_a_unix_socks_route_is_refused() {
    let mut endpoint = doh_endpoint(853);
    endpoint.protocol = EncryptedDnsProtocol::Doq;
    endpoint.doh_url = None;
    let socket = SocketPath::new();
    let resolver = EncryptedDnsResolver::new(endpoint, unix_transport(&socket, None)).expect("resolver builds");

    let error = resolver.exchange(&build_query("fixture.test")).await.expect_err("DoQ must be refused");

    assert!(matches!(&error, EncryptedDnsError::Request(message) if message.contains("DoQ")), "{error:?}");
}

#[derive(Clone, Copy)]
enum H2Reply {
    Answer,
    Oversized,
    OversizedStream,
    Redirect,
}

#[derive(Clone, Copy, PartialEq)]
enum FirstConnection {
    Normal,
    Drop,
    Freeze,
    ResetOnSecondRequest,
}

struct H2Fixture {
    port: u16,
    certificate: CertificateDer<'static>,
    connections: Arc<AtomicUsize>,
    negotiated_h2: Arc<AtomicUsize>,
    requests: Arc<AtomicUsize>,
    task: tokio::task::JoinHandle<()>,
}

type FixtureBody = http_body_util::combinators::BoxBody<bytes::Bytes, std::io::Error>;

fn full_body(bytes: Vec<u8>) -> FixtureBody {
    use http_body_util::BodyExt;

    http_body_util::Full::new(bytes::Bytes::from(bytes))
        .map_err(|never: std::convert::Infallible| -> std::io::Error { match never {} })
        .boxed()
}

async fn start_h2_fixture(answer: Vec<u8>, reply: H2Reply, first: FirstConnection) -> H2Fixture {
    use futures::StreamExt;
    use http_body_util::BodyExt;
    use hyper_util::rt::{TokioExecutor, TokioIo};

    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let (config, certificate) = tls_server_config(&[b"h2", b"http/1.1"]);
    let acceptor = tokio_rustls::TlsAcceptor::from(config);
    let (connections, negotiated_h2, requests) =
        (Arc::new(AtomicUsize::new(0)), Arc::new(AtomicUsize::new(0)), Arc::new(AtomicUsize::new(0)));
    let (seen_connections, seen_h2, seen_requests) = (connections.clone(), negotiated_h2.clone(), requests.clone());
    let task = tokio::spawn(async move {
        while let Ok((tcp, _)) = listener.accept().await {
            let index = seen_connections.fetch_add(1, Ordering::SeqCst);
            let (acceptor, answer) = (acceptor.clone(), answer.clone());
            let (seen_h2, seen_requests) = (seen_h2.clone(), seen_requests.clone());
            tokio::spawn(async move {
                let Ok(tls) = acceptor.accept(tcp).await else { return };
                if tls.get_ref().1.alpn_protocol() == Some(b"h2".as_slice()) {
                    seen_h2.fetch_add(1, Ordering::SeqCst);
                }
                let stop = Arc::new(tokio::sync::Notify::new());
                let freeze = Arc::new(tokio::sync::Notify::new());
                let (stopper, freezer) = (stop.clone(), freeze.clone());
                let service = hyper::service::service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                    let (answer, seen_requests) = (answer.clone(), seen_requests.clone());
                    let (stopper, freezer) = (stopper.clone(), freezer.clone());
                    async move {
                        seen_requests.fetch_add(1, Ordering::SeqCst);
                        let _ = request.into_body().collect().await;
                        let count = seen_requests.load(Ordering::SeqCst);
                        if index == 0 && first == FirstConnection::ResetOnSecondRequest && count == 2 {
                            stopper.notify_one();
                            std::future::pending::<()>().await;
                        }
                        if index == 0 && matches!(first, FirstConnection::Drop | FirstConnection::Freeze) {
                            tokio::spawn(async move {
                                tokio::time::sleep(Duration::from_millis(100)).await;
                                if first == FirstConnection::Freeze {
                                    freezer.notify_one();
                                } else {
                                    stopper.notify_one();
                                }
                            });
                        }
                        let builder =
                            hyper::Response::builder().header(hyper::header::CONTENT_TYPE, DNS_MESSAGE_MEDIA_TYPE);
                        let response = match reply {
                            H2Reply::Answer => builder.body(full_body(answer)),
                            H2Reply::Oversized => builder.body(full_body(vec![0u8; 65_536 + 1])),
                            H2Reply::OversizedStream => {
                                let chunks = futures::stream::iter(0..3).map(|_| {
                                    Ok::<_, std::io::Error>(hyper::body::Frame::data(bytes::Bytes::from(vec![
                                        0u8;
                                        30_000
                                    ])))
                                });
                                builder.body(BodyExt::boxed(http_body_util::StreamBody::new(chunks)))
                            }
                            H2Reply::Redirect => builder
                                .status(hyper::StatusCode::TEMPORARY_REDIRECT)
                                .header(hyper::header::LOCATION, "https://other.test/dns-query")
                                .body(full_body(Vec::new())),
                        };
                        response.map_err(|error| error.to_string())
                    }
                });
                let serving = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                    .serve_connection(TokioIo::new(tls), service);
                tokio::pin!(serving);
                loop {
                    tokio::select! {
                        _ = &mut serving => break,
                        _ = stop.notified() => break,
                        _ = freeze.notified() => std::future::pending::<()>().await,
                    }
                }
            });
        }
    });
    H2Fixture { port, certificate, connections, negotiated_h2, requests, task }
}

async fn finish_unix_proxy(proxy: thread::JoinHandle<()>, fixture: H2Fixture) {
    tokio::task::spawn_blocking(move || proxy.join().expect("proxy completes")).await.expect("join task");
    fixture.task.abort();
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_reuses_one_http2_connection() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 75));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, address_types) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 1, false);

    let resolver =
        doh_resolver(fixture.port, unix_transport(&socket, None), vec![fixture.certificate.clone()], DEFAULT_TIMEOUT);
    for _ in 0..2 {
        let reply = resolver.exchange(&query).await.expect("DoH response");
        assert_eq!(extract_ip_answers(&reply).expect("answers"), vec!["198.18.0.75"]);
    }

    assert_eq!(fixture.connections.load(Ordering::SeqCst), 1);
    assert_eq!(fixture.negotiated_h2.load(Ordering::SeqCst), 1);
    assert_eq!(fixture.requests.load(Ordering::SeqCst), 2);
    assert_eq!(*address_types.lock().expect("address types"), vec![0x03]);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_reconnects_after_the_server_drops_the_connection() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 76));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::Drop).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 2, false);

    let resolver =
        doh_resolver(fixture.port, unix_transport(&socket, None), vec![fixture.certificate.clone()], DEFAULT_TIMEOUT);
    resolver.exchange(&query).await.expect("first DoH response");
    tokio::time::sleep(Duration::from_millis(400)).await;
    let reply = resolver.exchange(&query).await.expect("second DoH response on a new connection");

    assert_eq!(extract_ip_answers(&reply).expect("answers"), vec!["198.18.0.76"]);
    assert_eq!(fixture.connections.load(Ordering::SeqCst), 2);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_rejects_an_oversized_http2_reply() {
    let query = build_query("fixture.test");
    let fixture = start_h2_fixture(Vec::new(), H2Reply::Oversized, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 1, false);

    let resolver =
        doh_resolver(fixture.port, unix_transport(&socket, None), vec![fixture.certificate.clone()], DEFAULT_TIMEOUT);
    let error = resolver.exchange(&query).await.expect_err("an oversized reply must fail");

    assert!(
        matches!(&error, EncryptedDnsError::Request(message) if message.contains("exceeds maximum size")),
        "{error:?}"
    );
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_does_not_follow_a_redirect() {
    let query = build_query("fixture.test");
    let fixture = start_h2_fixture(Vec::new(), H2Reply::Redirect, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 1, false);

    let resolver =
        doh_resolver(fixture.port, unix_transport(&socket, None), vec![fixture.certificate.clone()], DEFAULT_TIMEOUT);
    let error = resolver.exchange(&query).await.expect_err("a redirect must fail");

    assert!(
        matches!(error, EncryptedDnsError::HttpStatus(status) if status == reqwest::StatusCode::TEMPORARY_REDIRECT)
    );
    assert_eq!(fixture.requests.load(Ordering::SeqCst), 1);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, listener threads, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_falls_back_to_http1_when_the_server_lacks_h2() {
    let query = build_query("fixture.test");
    let response = build_response(&query, Ipv4Addr::new(198, 18, 0, 77));
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let (config, certificate) = tls_server_config(&[b"http/1.1"]);
    let (server_query, server_response) = (query.clone(), response.clone());
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh(stream, config, &server_query, &server_response);
    });
    let socket = SocketPath::new();
    let (proxy, address_types) = start_unix_socks_proxy(&socket, "fixture.test", port, 1, false);

    let resolver = doh_resolver(port, unix_transport(&socket, None), vec![certificate], DEFAULT_TIMEOUT);
    let answer = resolver.exchange(&query).await.expect("DoH response");

    assert_eq!(extract_ip_answers(&answer).expect("answers"), vec!["198.18.0.77"]);
    assert_eq!(*address_types.lock().expect("address types"), vec![0x03]);
    drop(resolver);
    proxy.join().expect("proxy completes");
    server.join().expect("server completes");
}

fn h2_resolver(fixture: &H2Fixture, socket: &SocketPath, timeout: Duration) -> EncryptedDnsResolver {
    doh_resolver(fixture.port, unix_transport(socket, None), vec![fixture.certificate.clone()], timeout)
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_recovers_from_a_server_that_goes_silent() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 78));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::Freeze).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 2, false);
    let timeout = Duration::from_millis(800);
    let resolver = h2_resolver(&fixture, &socket, timeout);

    resolver.exchange(&query).await.expect("first DoH response");
    while fixture.requests.load(Ordering::SeqCst) < 1 {
        tokio::task::yield_now().await;
    }
    tokio::time::sleep(Duration::from_millis(200)).await;
    let started = std::time::Instant::now();
    let second = resolver.exchange(&query).await;
    assert!(started.elapsed() < timeout + Duration::from_millis(300), "{:?}", started.elapsed());
    let third = resolver.exchange(&query).await.expect("a query after the silent one uses a new connection");

    assert_eq!(extract_ip_answers(&third).expect("answers"), vec!["198.18.0.78"]);
    drop(second);
    assert_eq!(fixture.connections.load(Ordering::SeqCst), 2);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn concurrent_doh_queries_over_a_unix_socks_route_share_one_connection() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 79));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 1, false);
    let resolver = h2_resolver(&fixture, &socket, DEFAULT_TIMEOUT);

    let replies = futures::future::join_all((0..6).map(|_| resolver.exchange(&query))).await;

    for reply in replies {
        assert_eq!(extract_ip_answers(&reply.expect("DoH response")).expect("answers"), vec!["198.18.0.79"]);
    }
    assert_eq!(fixture.connections.load(Ordering::SeqCst), 1);
    assert_eq!(fixture.requests.load(Ordering::SeqCst), 6);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_cuts_off_an_oversized_reply_without_content_length() {
    let query = build_query("fixture.test");
    let fixture = start_h2_fixture(Vec::new(), H2Reply::OversizedStream, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 1, false);
    let resolver = h2_resolver(&fixture, &socket, DEFAULT_TIMEOUT);

    let error = resolver.exchange(&query).await.expect_err("an oversized streamed reply must fail");

    assert!(
        matches!(&error, EncryptedDnsError::Request(message) if message.contains("exceeds maximum size")),
        "{error:?}"
    );
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_does_not_reuse_a_connection_for_another_authority() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 80));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 3, false);
    let resolver = h2_resolver(&fixture, &socket, DEFAULT_TIMEOUT);

    resolver.exchange(&query).await.expect("first DoH response");
    let other = format!("https://fixture.test:{}/dns-query", fixture.port + 1);
    resolver.post_dns_message_to_url(&other, &query).await.expect("second authority response");
    resolver.exchange(&query).await.expect("original authority again");

    assert_eq!(fixture.connections.load(Ordering::SeqCst), 3);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_rebuilds_once_after_a_reset_in_flight() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 81));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::ResetOnSecondRequest).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 2, false);
    let resolver = h2_resolver(&fixture, &socket, DEFAULT_TIMEOUT);

    resolver.exchange(&query).await.expect("first DoH response");
    let reply = resolver.exchange(&query).await.expect("a reset in flight is retried once");

    assert_eq!(extract_ip_answers(&reply).expect("answers"), vec!["198.18.0.81"]);
    assert_eq!(fixture.connections.load(Ordering::SeqCst), 2);
    assert_eq!(fixture.requests.load(Ordering::SeqCst), 3);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the resolver, fixture tasks, proxy thread, and socket path; cancellation drops them.
async fn doh_over_a_unix_socks_route_does_not_reuse_a_connection_built_on_another_runtime() {
    let query = build_query("fixture.test");
    let answer = build_response(&query, Ipv4Addr::new(198, 18, 0, 82));
    let fixture = start_h2_fixture(answer, H2Reply::Answer, FirstConnection::Normal).await;
    let socket = SocketPath::new();
    let (proxy, _) = start_unix_socks_proxy(&socket, "fixture.test", fixture.port, 2, false);
    let resolver = Arc::new(h2_resolver(&fixture, &socket, DEFAULT_TIMEOUT));

    resolver.exchange(&query).await.expect("first DoH response");
    let blocking = resolver.clone();
    let blocking_query = query.clone();
    let reply = tokio::task::spawn_blocking(move || {
        thread::spawn(move || blocking.exchange_blocking(&blocking_query)).join().expect("blocking thread")
    })
    .await
    .expect("join task")
    .expect("blocking DoH response");

    assert_eq!(extract_ip_answers(&reply).expect("answers"), vec!["198.18.0.82"]);
    assert_eq!(fixture.connections.load(Ordering::SeqCst), 2);
    drop(resolver);
    finish_unix_proxy(proxy, fixture).await;
}
