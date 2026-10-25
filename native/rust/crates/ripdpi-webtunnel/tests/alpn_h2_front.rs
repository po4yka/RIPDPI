//! A bridge front with HTTP/2 enabled (nginx `listen 443 ssl http2`, Caddy, a
//! CDN) selects h2 when the client offers it. The WebTunnel upgrade is an
//! HTTP/1.1 request, so the client must not send it on an h2 connection.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use boring::asn1::Asn1Time;
use boring::bn::BigNum;
use boring::ec::{EcGroup, EcKey};
use boring::hash::MessageDigest;
use boring::nid::Nid;
use boring::pkey::PKey;
use boring::ssl::{self, SslAcceptor, SslMethod};
use boring::x509::extension::SubjectAlternativeName;
use boring::x509::{X509, X509NameBuilder};
use ripdpi_webtunnel::bridge_line::{WebTunnelBridgeConfig, parse_bridge_line};
use ripdpi_webtunnel::client::{connect_webtunnel, connect_webtunnel_async};

/// One observed TLS connection: the ALPN the front selected and the first
/// request line the client sent on it (if any).
#[derive(Debug, Clone, PartialEq, Eq)]
struct Observed {
    alpn: Option<String>,
    request_line: Option<String>,
}

struct H2PreferringFront {
    addr: SocketAddr,
    root_pem: String,
    observed: Arc<Mutex<Vec<Observed>>>,
}

fn self_signed_localhost() -> (X509, PKey<boring::pkey::Private>) {
    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1).expect("ec group");
    let key = PKey::from_ec_key(EcKey::generate(&group).expect("ec key")).expect("pkey");
    let mut name = X509NameBuilder::new().expect("name builder");
    name.append_entry_by_nid(Nid::COMMONNAME, "localhost").expect("common name");
    let name = name.build();
    let mut builder = X509::builder().expect("x509 builder");
    builder.set_version(2).expect("version");
    builder.set_serial_number(&BigNum::from_u32(1).expect("bn").to_asn1_integer().expect("serial")).expect("serial");
    builder.set_subject_name(&name).expect("subject");
    builder.set_issuer_name(&name).expect("issuer");
    builder.set_pubkey(&key).expect("pubkey");
    builder.set_not_before(&Asn1Time::days_from_now(0).expect("not before")).expect("not before");
    builder.set_not_after(&Asn1Time::days_from_now(1).expect("not after")).expect("not after");
    let san = SubjectAlternativeName::new().dns("localhost").build(&builder.x509v3_context(None, None)).expect("san");
    builder.append_extension(&san).expect("append san");
    builder.sign(&key, MessageDigest::sha256()).expect("sign");
    (builder.build(), key)
}

/// Starts a TLS front that prefers h2 like an HTTP/2-enabled nginx. On an h2
/// connection it answers with an HTTP/2 SETTINGS frame and closes; on an
/// HTTP/1.1 connection it accepts the upgrade and echoes the tunnel bytes.
fn start_h2_preferring_front() -> H2PreferringFront {
    let (cert, key) = self_signed_localhost();
    let root_pem = String::from_utf8(cert.to_pem().expect("cert pem")).expect("pem utf8");
    let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).expect("acceptor");
    acceptor.set_certificate(&cert).expect("cert");
    acceptor.set_private_key(&key).expect("key");
    acceptor.set_alpn_select_callback(|_, client| {
        ssl::select_next_proto(b"\x02h2\x08http/1.1", client).ok_or(ssl::AlpnError::NOACK)
    });
    let acceptor = acceptor.build();

    let listener = TcpListener::bind("127.0.0.1:0").expect("bind front");
    let addr = listener.local_addr().expect("front addr");
    let observed = Arc::new(Mutex::new(Vec::new()));
    let record = Arc::clone(&observed);
    thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { return };
            stream.set_read_timeout(Some(Duration::from_secs(5))).expect("front read timeout");
            let Ok(mut tls) = acceptor.accept(stream) else { continue };
            let alpn = tls.ssl().selected_alpn_protocol().map(|value| String::from_utf8_lossy(value).into_owned());
            if alpn.as_deref() == Some("h2") {
                // Server connection preface: an empty SETTINGS frame. Read
                // whatever the client sends first so the test can see it.
                let _ = tls.write_all(&[0, 0, 0, 0x04, 0, 0, 0, 0, 0]);
                let mut head = [0_u8; 64];
                let request_line = match tls.read(&mut head) {
                    Ok(read) if read > 0 => Some(first_line(&head[..read])),
                    _ => None,
                };
                record.lock().expect("observed").push(Observed { alpn, request_line });
                let _ = tls.shutdown();
                continue;
            }
            let mut head = Vec::new();
            let mut byte = [0_u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                if tls.read_exact(&mut byte).is_err() {
                    break;
                }
                head.push(byte[0]);
            }
            record.lock().expect("observed").push(Observed { alpn, request_line: Some(first_line(&head)) });
            if tls
                .write_all(b"HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\n")
                .is_err()
            {
                continue;
            }
            let mut payload = [0_u8; 64];
            if let Ok(read) = tls.read(&mut payload) {
                let _ = tls.write_all(&payload[..read]);
            }
        }
    });
    H2PreferringFront { addr, root_pem, observed }
}

fn first_line(bytes: &[u8]) -> String {
    let text = String::from_utf8_lossy(bytes);
    text.split("\r\n").next().unwrap_or_default().to_owned()
}

fn bridge(front: &H2PreferringFront) -> WebTunnelBridgeConfig {
    let mut bridge = parse_bridge_line(&format!(
        "Bridge webtunnel 192.0.2.3:1 url=https://localhost:{port}/secret addr={addr} servername=localhost utls=hellochrome_auto",
        port = front.addr.port(),
        addr = front.addr,
    ))
    .expect("bridge line");
    bridge.root_certificate_pem = Some(front.root_pem.clone());
    bridge
}

fn assert_secret_path_only_sent_over_http11(front: &H2PreferringFront) {
    let observed = front.observed.lock().expect("observed").clone();
    let upgrades: Vec<_> = observed.iter().filter(|entry| entry.request_line.is_some()).collect();
    assert!(!upgrades.is_empty(), "no request reached the front: {observed:?}");
    for entry in &observed {
        if entry.alpn.as_deref() == Some("h2") {
            assert_eq!(entry.request_line, None, "secret path sent on an h2 connection: {observed:?}");
        }
    }
    assert_eq!(
        observed.last(),
        Some(&Observed { alpn: Some("http/1.1".to_owned()), request_line: Some("GET /secret HTTP/1.1".to_owned()) }),
        "{observed:?}"
    );
}

#[test]
fn webtunnel_client_upgrades_over_http11_when_the_front_prefers_h2() {
    let front = start_h2_preferring_front();

    let mut stream = connect_webtunnel(&bridge(&front)).expect("connect through an h2-enabled front");
    stream.write_all(b"ping").expect("write tunnel payload");
    let mut echoed = [0_u8; 4];
    stream.read_exact(&mut echoed).expect("read echoed payload");

    assert_eq!(&echoed, b"ping");
    assert_secret_path_only_sent_over_http11(&front);
}

#[tokio::test]
async fn webtunnel_async_client_upgrades_over_http11_when_the_front_prefers_h2() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let front = start_h2_preferring_front();

    let mut stream = connect_webtunnel_async(&bridge(&front)).await.expect("connect through an h2-enabled front");
    stream.write_all(b"ping").await.expect("write tunnel payload");
    let mut echoed = [0_u8; 4];
    stream.read_exact(&mut echoed).await.expect("read echoed payload");

    assert_eq!(&echoed, b"ping");
    assert_secret_path_only_sent_over_http11(&front);
}
