use std::net::{Ipv6Addr, SocketAddr};
use std::sync::Mutex;

use super::*;

const ANSWER: Ipv4Addr = Ipv4Addr::new(192, 0, 2, 53);
/// OPT RR with one empty edns-tcp-keepalive option (code 11, RFC 7828).
const OPT_KEEPALIVE: [u8; 15] = [0, 0x00, 0x29, 0x10, 0x00, 0, 0, 0, 0, 0x00, 0x04, 0x00, 0x0b, 0x00, 0x00];

type Transform = Arc<dyn Fn(&[u8]) -> Vec<u8> + Send + Sync>;
type Closes = Arc<Mutex<Vec<Option<quinn::ConnectionError>>>>;

fn server_config() -> (quinn::ServerConfig, CertificateDer<'static>) {
    let certificate = generate_simple_self_signed(vec!["localhost".into()]).expect("certificate");
    let cert = certificate.cert.der().clone();
    let mut tls = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into())
        .expect("server config");
    tls.alpn_protocols = vec![b"doq".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC server config");
    (quinn::ServerConfig::with_crypto(Arc::new(crypto)), cert)
}

/// A minimal RFC 9250 server: one query per bidirectional stream, 2-byte length framing, FIN after the reply.
/// `Closes` receives each connection's close reason once the connection ends; the counter counts accepted connections.
fn spawn_server(bind: SocketAddr, transform: Transform) -> (u16, CertificateDer<'static>, Closes, Arc<AtomicUsize>) {
    let (config, cert) = server_config();
    let server = quinn::Endpoint::server(config, bind).expect("DoQ server");
    let port = server.local_addr().expect("server address").port();
    let closes: Closes = Arc::new(Mutex::new(Vec::new()));
    let recorded = closes.clone();
    let accepted = Arc::new(AtomicUsize::new(0));
    let counted = accepted.clone();
    tokio::spawn(async move {
        while let Some(incoming) = server.accept().await {
            let transform = transform.clone();
            let recorded = recorded.clone();
            let counted = counted.clone();
            tokio::spawn(async move {
                let Ok(conn) = incoming.await else { return };
                counted.fetch_add(1, Ordering::SeqCst);
                while let Ok((mut send, mut recv)) = conn.accept_bi().await {
                    let mut len = [0u8; 2];
                    if recv.read_exact(&mut len).await.is_err() {
                        continue;
                    }
                    let mut query = vec![0u8; usize::from(u16::from_be_bytes(len))];
                    if recv.read_exact(&mut query).await.is_err() {
                        continue;
                    }
                    let reply = transform(&query);
                    let mut framed = u16::try_from(reply.len()).expect("reply fits").to_be_bytes().to_vec();
                    framed.extend_from_slice(&reply);
                    let _ = send.write_all(&framed).await;
                    let _ = send.finish();
                }
                recorded.lock().expect("closes lock").push(conn.close_reason());
            });
        }
    });
    (port, cert, closes, accepted)
}

fn doq_endpoint(port: u16, bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doq,
        resolver_id: None,
        host: "localhost".into(),
        port,
        tls_server_name: None,
        bootstrap_ips,
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

fn resolver(port: u16, cert: CertificateDer<'static>, bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_extra_tls_roots(
        doq_endpoint(port, bootstrap_ips),
        EncryptedDnsTransport::Direct,
        Duration::from_millis(700),
        vec![cert],
    )
    .expect("resolver builds")
}

fn answer_ips(response: &[u8]) -> Vec<Ipv4Addr> {
    Message::from_vec(response)
        .expect("response parses")
        .answers
        .iter()
        .filter_map(|record| match &record.data {
            RData::A(a) => Some(a.0),
            _ => None,
        })
        .collect()
}

fn honest() -> Transform {
    Arc::new(|query: &[u8]| build_response(query, ANSWER))
}

#[tokio::test]
async fn doq_reaches_ipv6_server_when_ipv4_bootstrap_ip_is_listed_first() {
    let (port, cert, _, _) = spawn_server((Ipv6Addr::LOCALHOST, 0).into(), honest());
    let resolver = resolver(port, cert, vec![IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)]);
    let response = resolver.exchange(&build_query("example.test")).await.expect("IPv6 DoQ server is reachable");
    assert_eq!(answer_ips(&response), vec![ANSWER]);
}

#[tokio::test]
async fn doq_reaches_ipv4_server_when_ipv6_bootstrap_ip_is_listed_first() {
    let (port, cert, _, _) = spawn_server((Ipv4Addr::LOCALHOST, 0).into(), honest());
    let resolver = resolver(port, cert, vec![IpAddr::V6(Ipv6Addr::LOCALHOST), IpAddr::V4(Ipv4Addr::LOCALHOST)]);
    let response = resolver.exchange(&build_query("example.test")).await.expect("IPv4 DoQ server is reachable");
    assert_eq!(answer_ips(&response), vec![ANSWER]);
}

/// A host without IPv6 sockets: the binder refuses `[::]`, so DoQ falls back to an IPv4 socket.
#[tokio::test]
async fn doq_falls_back_to_ipv4_socket_when_ipv6_bind_fails() {
    let (port, cert, _, _) = spawn_server((Ipv4Addr::LOCALHOST, 0).into(), honest());
    let binds = Arc::new(Mutex::new(Vec::new()));
    let seen = binds.clone();
    let hooks = EncryptedDnsConnectHooks::new().with_direct_udp_binder(move |bind_addr| {
        seen.lock().expect("binds lock").push(bind_addr);
        if bind_addr.is_ipv6() {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "no IPv6 sockets"));
        }
        std::net::UdpSocket::bind(bind_addr)
    });
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        doq_endpoint(port, vec![IpAddr::V6(Ipv6Addr::LOCALHOST), IpAddr::V4(Ipv4Addr::LOCALHOST)]),
        EncryptedDnsTransport::Direct,
        Duration::from_millis(700),
        vec![cert],
        hooks,
    )
    .expect("resolver builds");

    let response = resolver.exchange(&build_query("example.test")).await.expect("IPv4 bootstrap IP still works");

    assert_eq!(answer_ips(&response), vec![ANSWER]);
    let expected: Vec<SocketAddr> = vec!["[::]:0".parse().unwrap(), "0.0.0.0:0".parse().unwrap()];
    assert_eq!(*binds.lock().expect("binds lock"), expected);
}

#[tokio::test]
async fn doq_rejects_mismatched_replies_and_keeps_the_connection() {
    let calls = Arc::new(AtomicUsize::new(0));
    let transform: Transform = Arc::new(move |query: &[u8]| match calls.fetch_add(1, Ordering::SeqCst) {
        0 => {
            let mut reply = build_response(&build_query("other.test"), ANSWER);
            reply[..2].copy_from_slice(&[0, 0]);
            reply
        }
        // A QR=0 echo of the query is not a response.
        1 => query.to_vec(),
        _ => build_response(query, ANSWER),
    });
    let (port, cert, closes, accepted) = spawn_server((Ipv4Addr::LOCALHOST, 0).into(), transform);
    let resolver = resolver(port, cert, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]);
    let query = build_query("example.test");

    assert!(resolver.exchange(&query).await.is_err(), "reply for another question must be rejected");
    assert!(resolver.exchange(&query).await.is_err(), "QR=0 echo must be rejected");
    let response = resolver.exchange(&query).await.expect("same connection still serves a correct reply");
    assert_eq!(answer_ips(&response), vec![ANSWER]);
    let closes = closes.lock().expect("closes lock");
    assert!(closes.is_empty(), "a transaction error must not close the connection: {closes:?}");
    assert_eq!(accepted.load(Ordering::SeqCst), 1, "all three queries must share one connection");
}

#[tokio::test]
async fn doq_closes_connection_on_response_edns_tcp_keepalive() {
    let transform: Transform = Arc::new(|query: &[u8]| {
        let mut reply = build_response(query, ANSWER);
        let arcount = u16::from_be_bytes([reply[10], reply[11]]) + 1;
        reply[10..12].copy_from_slice(&arcount.to_be_bytes());
        reply.extend_from_slice(&OPT_KEEPALIVE);
        reply
    });
    let (port, cert, closes, _) = spawn_server((Ipv4Addr::LOCALHOST, 0).into(), transform);
    let resolver = resolver(port, cert, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]);

    assert!(resolver.exchange(&build_query("example.test")).await.is_err(), "keepalive response must be rejected");
    for _ in 0..40 {
        if !closes.lock().expect("closes lock").is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let closes = closes.lock().expect("closes lock");
    assert!(
        matches!(
            closes.as_slice(),
            [Some(quinn::ConnectionError::ApplicationClosed(close))] if close.error_code == quinn::VarInt::from_u32(2)
        ),
        "expected one DOQ_PROTOCOL_ERROR close, got {closes:?}"
    );
}

#[test]
fn doq_endpoint_must_not_use_udp_port_53() {
    let ips = vec![IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1))];
    assert!(EncryptedDnsResolver::new(doq_endpoint(53, ips.clone()), EncryptedDnsTransport::Direct).is_err());
    assert!(EncryptedDnsResolver::new(doq_endpoint(853, ips), EncryptedDnsTransport::Direct).is_ok());
}
