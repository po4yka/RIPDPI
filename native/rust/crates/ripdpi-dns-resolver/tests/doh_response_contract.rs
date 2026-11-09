//! DoH response contract (RFC 8484 section 4.2) on the manual HTTP/1.1 path (hooked
//! Direct) and the reqwest path (hookless Direct), plus SOCKS5 connect hooks.
//! The DNS bytes below are written by hand, not with the crate's builders.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use ripdpi_dns_resolver::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
    ResolverPool,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

// example.test A IN, ID 0xBEEF, RD
const QUERY: &[u8] = &[
    0xBE, 0xEF, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 7, b'e', b'x', b'a', b'm', b'p', b'l',
    b'e', 4, b't', b'e', b's', b't', 0, 0x00, 0x01, 0x00, 0x01,
];
// Same ID and question, QR|RD|RA, one answer 192.0.2.7 ttl 60
const GOOD_REPLY: &[u8] = &[
    0xBE, 0xEF, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 7, b'e', b'x', b'a', b'm', b'p', b'l',
    b'e', 4, b't', b'e', b's', b't', 0, 0x00, 0x01, 0x00, 0x01, 0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x3C, 0x00, 0x04, 192, 0, 2, 7,
];
// Wrong ID (0x1234) and wrong question (other.test)
const MISMATCHED_REPLY: &[u8] = &[
    0x12, 0x34, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 5, b'o', b't', b'h', b'e', b'r', 4, b't',
    b'e', b's', b't', 0, 0x00, 0x01, 0x00, 0x01, 0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00,
    0x04, 203, 0, 113, 66,
];
// Right ID, wrong question (other.test)
const WRONG_QUESTION_REPLY: &[u8] = &[
    0xBE, 0xEF, 0x81, 0x80, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 5, b'o', b't', b'h', b'e', b'r', 4, b't',
    b'e', b's', b't', 0, 0x00, 0x01, 0x00, 0x01,
];

const V4: IpAddr = IpAddr::V4(Ipv4Addr::LOCALHOST);
const V6: IpAddr = IpAddr::V6(Ipv6Addr::LOCALHOST);
const DNS_MESSAGE: &str = "application/dns-message";

struct Seen {
    head: String,
    body: Vec<u8>,
}

async fn read_request(stream: &mut TcpStream) -> Seen {
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") {
        head.push(stream.read_u8().await.expect("request byte"));
    }
    let head = String::from_utf8(head).expect("ascii head");
    let length = head
        .lines()
        .find_map(|line| line.to_ascii_lowercase().strip_prefix("content-length:").map(|v| v.trim().to_string()))
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(0);
    let mut body = vec![0; length];
    stream.read_exact(&mut body).await.expect("request body");
    Seen { head, body }
}

/// Serves every connection with the same raw response and reports each request.
async fn spawn_server(bind: IpAddr, response: Vec<u8>) -> (u16, mpsc::UnboundedReceiver<Seen>) {
    let listener = TcpListener::bind(SocketAddr::new(bind, 0)).await.expect("bind");
    let port = listener.local_addr().expect("addr").port();
    let (tx, rx) = mpsc::unbounded_channel();
    tokio::spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else { return };
            let tx = tx.clone();
            let response = response.clone();
            tokio::spawn(async move {
                let seen = read_request(&mut stream).await;
                let _ = tx.send(seen);
                let _ = stream.write_all(&response).await;
                let _ = stream.shutdown().await;
            });
        }
    });
    (port, rx)
}

fn http_response(status: &str, content_type: Option<&str>, body: &[u8]) -> Vec<u8> {
    let mut out = format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n", body.len());
    if let Some(content_type) = content_type {
        out.push_str(&format!("Content-Type: {content_type}\r\n"));
    }
    out.push_str("\r\n");
    let mut out = out.into_bytes();
    out.extend_from_slice(body);
    out
}

fn endpoint(ip: IpAddr, port: u16) -> EncryptedDnsEndpoint {
    let host = match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{v6}]"),
    };
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some("contract".into()),
        host: ip.to_string(),
        port,
        tls_server_name: None,
        bootstrap_ips: vec![ip],
        doh_url: Some(format!("http://{host}:{port}/dns-query")),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

/// Manual HTTP/1.1 path: Direct with a direct TCP connector hook.
fn manual(ip: IpAddr, port: u16) -> EncryptedDnsResolver {
    let hooks = EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(|target: SocketAddr, _timeout| async move { std::net::TcpStream::connect(target) });
    EncryptedDnsResolver::with_timeout_and_connect_hooks(
        endpoint(ip, port),
        EncryptedDnsTransport::Direct,
        Duration::from_secs(2),
        hooks,
    )
    .expect("manual resolver")
}

/// reqwest path: Direct without hooks.
fn reqwest_direct(ip: IpAddr, port: u16) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_timeout(endpoint(ip, port), EncryptedDnsTransport::Direct, Duration::from_secs(2))
        .expect("reqwest resolver")
}

fn both_paths(ip: IpAddr, port: u16) -> [(&'static str, EncryptedDnsResolver); 2] {
    [("manual", manual(ip, port)), ("reqwest", reqwest_direct(ip, port))]
}

#[tokio::test]
async fn good_reply_is_accepted_with_and_without_content_type() {
    for ip in [V4, V6] {
        for content_type in
            [Some(DNS_MESSAGE), None, Some("application/dns-message; charset=x"), Some("Application/DNS-Message")]
        {
            let (port, mut seen) = spawn_server(ip, http_response("200 OK", content_type, GOOD_REPLY)).await;
            for (path, resolver) in both_paths(ip, port) {
                let reply =
                    resolver.exchange(QUERY).await.unwrap_or_else(|e| panic!("{ip}/{path}/{content_type:?}: {e}"));
                assert_eq!(reply, GOOD_REPLY, "{ip}/{path}/{content_type:?}");
                let request = seen.recv().await.expect("request seen");
                let head = request.head.to_ascii_lowercase();
                assert!(head.starts_with("post /dns-query http/1.1\r\n"), "{ip}/{path}: {head}");
                assert!(head.contains("content-type: application/dns-message"), "{ip}/{path}");
                assert!(head.contains("accept: application/dns-message"), "{ip}/{path}");
                assert_eq!(request.body[..2], QUERY[..2], "{ip}/{path}: body starts with the query ID");
            }
        }
    }
}

#[tokio::test]
async fn wrong_content_type_is_rejected() {
    let (port, _seen) = spawn_server(V4, http_response("200 OK", Some("text/html"), GOOD_REPLY)).await;
    for (path, resolver) in both_paths(V4, port) {
        let error = resolver.exchange(QUERY).await.expect_err("text/html must fail");
        assert!(error.to_string().contains("unexpected Content-Type"), "{path}: {error}");
    }
    let (port, _seen) = spawn_server(V4, http_response("200 OK", Some("text/html"), b"<html>portal</html>")).await;
    for (path, resolver) in both_paths(V4, port) {
        assert!(resolver.exchange(QUERY).await.is_err(), "{path}: HTML body must fail");
    }
}

#[tokio::test]
async fn empty_truncated_echoed_and_mismatched_replies_are_rejected() {
    let truncated = &GOOD_REPLY[..7];
    let bodies: [(&str, &str, &[u8]); 6] = [
        ("200 OK", "empty 200", b""),
        ("204 No Content", "204", b""),
        ("200 OK", "truncated", truncated),
        ("200 OK", "echoed query", QUERY),
        ("200 OK", "wrong ID and question", MISMATCHED_REPLY),
        ("200 OK", "wrong question", WRONG_QUESTION_REPLY),
    ];
    for ip in [V4, V6] {
        for (status, name, body) in bodies {
            let (port, _seen) = spawn_server(ip, http_response(status, Some(DNS_MESSAGE), body)).await;
            for (path, resolver) in both_paths(ip, port) {
                assert!(resolver.exchange(QUERY).await.is_err(), "{ip}/{path}: {name} must fail");
            }
        }
    }
}

#[tokio::test]
async fn pool_fails_over_from_a_garbage_200() {
    let (bad_port, _bad) = spawn_server(V4, http_response("200 OK", Some("text/html"), b"<html/>")).await;
    let (good_port, _good) = spawn_server(V4, http_response("200 OK", Some(DNS_MESSAGE), GOOD_REPLY)).await;
    let pool = ResolverPool::builder()
        .timeout(Duration::from_secs(2))
        .add_endpoint(endpoint(V4, bad_port), EncryptedDnsTransport::Direct)
        .add_endpoint(endpoint(V4, good_port), EncryptedDnsTransport::Direct)
        .build()
        .expect("pool");
    let success = pool.exchange(QUERY).await.expect("pool success");
    assert_eq!(success.response_bytes, GOOD_REPLY);
}

/// Minimal SOCKS5 server (no auth) that connects to the requested target.
async fn spawn_socks5(bind: IpAddr) -> u16 {
    let listener = TcpListener::bind(SocketAddr::new(bind, 0)).await.expect("bind socks");
    let port = listener.local_addr().expect("addr").port();
    tokio::spawn(async move {
        loop {
            let Ok((mut client, _)) = listener.accept().await else { return };
            tokio::spawn(async move {
                let mut greet = [0u8; 2];
                client.read_exact(&mut greet).await.ok()?;
                let mut methods = vec![0; greet[1] as usize];
                client.read_exact(&mut methods).await.ok()?;
                client.write_all(&[5, 0]).await.ok()?;
                let mut req = [0u8; 4];
                client.read_exact(&mut req).await.ok()?;
                let target_ip: IpAddr = match req[3] {
                    1 => {
                        let mut a = [0u8; 4];
                        client.read_exact(&mut a).await.ok()?;
                        IpAddr::from(a)
                    }
                    4 => {
                        let mut a = [0u8; 16];
                        client.read_exact(&mut a).await.ok()?;
                        IpAddr::from(a)
                    }
                    3 => {
                        let len = client.read_u8().await.ok()?;
                        let mut name = vec![0; len as usize];
                        client.read_exact(&mut name).await.ok()?;
                        String::from_utf8(name).ok()?.trim_matches(['[', ']']).parse().ok()?
                    }
                    _ => return None,
                };
                let port = client.read_u16().await.ok()?;
                let mut upstream = TcpStream::connect(SocketAddr::new(target_ip, port)).await.ok()?;
                client.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await.ok()?;
                let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
                Some(())
            });
        }
    });
    port
}

fn socks_resolver(
    doh_port: u16,
    proxy_host: &str,
    proxy_port: u16,
    hooks: EncryptedDnsConnectHooks,
) -> EncryptedDnsResolver {
    let mut endpoint = endpoint(V4, doh_port);
    endpoint.bootstrap_ips.clear();
    EncryptedDnsResolver::with_timeout_and_connect_hooks(
        endpoint,
        EncryptedDnsTransport::Socks5 { host: proxy_host.to_string(), port: proxy_port, credentials: None },
        Duration::from_secs(2),
        hooks,
    )
    .expect("socks resolver")
}

#[tokio::test]
async fn socks5_doh_routes_a_non_loopback_proxy_connect_through_the_hook() {
    let (doh_port, _seen) = spawn_server(V4, http_response("200 OK", Some(DNS_MESSAGE), GOOD_REPLY)).await;
    let socks_port = spawn_socks5(V4).await;
    let targets: Arc<std::sync::Mutex<Vec<SocketAddr>>> = Arc::default();
    let recorded = targets.clone();
    let hooks = EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(move |target: SocketAddr, _timeout| {
            recorded.lock().expect("targets lock").push(target);
            async move { std::net::TcpStream::connect((Ipv4Addr::LOCALHOST, socks_port)) }
        })
        .require_direct_tcp_connector();
    let resolver = socks_resolver(doh_port, "192.0.2.1", 1080, hooks);

    assert_eq!(resolver.exchange(QUERY).await.expect("exchange through hooked proxy connect"), GOOD_REPLY);
    let targets = targets.lock().expect("targets lock");
    assert!(!targets.is_empty());
    assert!(targets.iter().all(|target| *target == SocketAddr::from(([192, 0, 2, 1], 1080))), "{targets:?}");
}

#[tokio::test]
async fn socks5_doh_fails_closed_for_a_non_loopback_proxy_without_connector() {
    let (doh_port, mut seen) = spawn_server(V4, http_response("200 OK", Some(DNS_MESSAGE), GOOD_REPLY)).await;
    let resolver =
        socks_resolver(doh_port, "192.0.2.1", 1080, EncryptedDnsConnectHooks::new().require_direct_tcp_connector());

    let error = resolver.exchange(QUERY).await.expect_err("missing connector must fail closed");
    assert!(error.to_string().contains("direct TCP connector is required"), "{error}");
    assert!(seen.try_recv().is_err(), "nothing may be dialed");
}

#[tokio::test]
async fn socks5_doh_with_a_loopback_proxy_still_works_with_hooks() {
    let (doh_port, _seen) = spawn_server(V4, http_response("200 OK", Some(DNS_MESSAGE), GOOD_REPLY)).await;
    let socks_port = spawn_socks5(V4).await;
    let calls = Arc::new(AtomicUsize::new(0));
    let counted = calls.clone();
    let hooks = EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(move |target: SocketAddr, _timeout| {
            counted.fetch_add(1, Ordering::SeqCst);
            async move { std::net::TcpStream::connect(target) }
        })
        .require_direct_tcp_connector();
    let socks_v6_port = spawn_socks5(V6).await;
    for (proxy_host, proxy_port) in [("127.0.0.1", socks_port), ("::1", socks_v6_port), ("[::1]", socks_v6_port)] {
        let resolver = socks_resolver(doh_port, proxy_host, proxy_port, hooks.clone());
        assert_eq!(resolver.exchange(QUERY).await.expect("loopback proxy exchange"), GOOD_REPLY, "{proxy_host}");
    }
    // A loopback proxy needs no protect and keeps the reqwest (HTTP/2-capable) client.
    assert_eq!(calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn socks5_doh_resolves_a_hostname_proxy_and_dials_it_through_the_hook() {
    let (doh_port, _seen) = spawn_server(V4, http_response("200 OK", Some(DNS_MESSAGE), GOOD_REPLY)).await;
    let socks_port = spawn_socks5(V4).await;
    let targets: Arc<std::sync::Mutex<Vec<SocketAddr>>> = Arc::default();
    let recorded = targets.clone();
    let hooks = EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(move |target: SocketAddr, _timeout| {
            recorded.lock().expect("targets lock").push(target);
            async move { std::net::TcpStream::connect((Ipv4Addr::LOCALHOST, socks_port)) }
        })
        .require_direct_tcp_connector();
    // A hostname is not a loopback IP literal: the manual path resolves it and dials through the hook.
    let resolver = socks_resolver(doh_port, "localhost", socks_port, hooks);

    assert_eq!(resolver.exchange(QUERY).await.expect("hostname proxy exchange"), GOOD_REPLY);
    let targets = targets.lock().expect("targets lock");
    assert!(!targets.is_empty());
    assert!(targets.iter().all(|target| target.ip().is_loopback() && target.port() == socks_port), "{targets:?}");
}
