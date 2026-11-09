//! ODoH response contract (RFC 9230 sections 4.3 and 6.2) on the manual HTTP/1.1 path (hooked
//! Direct) and the reqwest path (hookless Direct). The DNS bytes are written by hand.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use bytes::Bytes;
use odoh_rs::{
    ObliviousDoHKeyPair, ObliviousDoHMessage, ObliviousDoHMessagePlaintext, compose, decrypt_query, encrypt_response,
    parse,
};
use ripdpi_dns_resolver::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsResolver,
    EncryptedDnsTransport, ODOH_MESSAGE_MEDIA_TYPE, OdohConfigSource, OdohEndpointConfig,
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

const PUBLIC_KEY_SEED: &str = "c9d84d04e6369fccb8a4d5a264001491221f1b97d9b80dd32c35834bb4462383";
const ODOHCONFIGS: &str =
    "002c000100280020000100010020c6a793bedbd601c25970b1cc46bea80fdb1a8ec51540d79e4f9f17b8baa9da33";
const LOCALHOST: IpAddr = IpAddr::V4(Ipv4Addr::LOCALHOST);

async fn read_request(stream: &mut TcpStream) -> (String, Vec<u8>) {
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
    (head, body)
}

/// Decrypts each ODoH request, answers with `dns_reply` encrypted for it, and reports the request line.
async fn spawn_target(
    dns_reply: &'static [u8],
    content_type: Option<&'static str>,
) -> (u16, mpsc::UnboundedReceiver<String>) {
    let listener = TcpListener::bind(SocketAddr::new(LOCALHOST, 0)).await.expect("bind");
    let port = listener.local_addr().expect("addr").port();
    let (tx, rx) = mpsc::unbounded_channel();
    tokio::spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else { return };
            let tx = tx.clone();
            tokio::spawn(async move {
                let (head, body) = read_request(&mut stream).await;
                let _ = tx.send(head);
                let key_pair =
                    ObliviousDoHKeyPair::from_parameters(32, 1, 1, &hex::decode(PUBLIC_KEY_SEED).expect("seed"));
                let mut wire: Bytes = body.into();
                let message: ObliviousDoHMessage = parse(&mut wire).expect("ODoH request parses");
                let (plain_query, secret) = decrypt_query(&message, &key_pair).expect("request decrypts");
                let plaintext = ObliviousDoHMessagePlaintext::new(dns_reply, 0);
                let reply = encrypt_response(&plain_query, &plaintext, secret, [9; 16]).expect("response encrypts");
                let reply = compose(&reply).expect("response serializes");
                let mut out = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n", reply.len());
                if let Some(content_type) = content_type {
                    out.push_str(&format!("Content-Type: {content_type}\r\n"));
                }
                out.push_str("\r\n");
                let mut out = out.into_bytes();
                out.extend_from_slice(&reply);
                let _ = stream.write_all(&out).await;
                let _ = stream.shutdown().await;
            });
        }
    });
    (port, rx)
}

fn endpoint(port: u16) -> EncryptedDnsEndpoint {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).expect("clock").as_secs();
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Odoh,
        resolver_id: Some("odoh-contract".into()),
        host: String::new(),
        port: 0,
        tls_server_name: None,
        bootstrap_ips: vec![LOCALHOST],
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: Some(OdohEndpointConfig {
            proxy_url: format!("http://127.0.0.1:{port}/proxy"),
            proxy_operator_id: "proxy-op".into(),
            target_host: "target.example".into(),
            target_path: "/dns-query".into(),
            target_operator_id: "target-op".into(),
            config_source: OdohConfigSource::CustomBytes {
                configs_wire: hex::decode(ODOHCONFIGS).expect("config hex"),
                retrieved_at_secs: now - 10,
                ttl_secs: 3600,
            },
        }),
    }
}

fn both_paths(port: u16) -> [(&'static str, EncryptedDnsResolver); 2] {
    let hooks = EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(|target: SocketAddr, _timeout| async move { std::net::TcpStream::connect(target) });
    let manual = EncryptedDnsResolver::with_timeout_and_connect_hooks(
        endpoint(port),
        EncryptedDnsTransport::Direct,
        Duration::from_secs(2),
        hooks,
    )
    .expect("manual resolver");
    let reqwest =
        EncryptedDnsResolver::with_timeout(endpoint(port), EncryptedDnsTransport::Direct, Duration::from_secs(2))
            .expect("reqwest resolver");
    [("manual", manual), ("reqwest", reqwest)]
}

#[tokio::test]
async fn good_reply_with_content_type_is_accepted_and_request_carries_target() {
    let (port, mut seen) = spawn_target(GOOD_REPLY, Some(ODOH_MESSAGE_MEDIA_TYPE)).await;
    for (path, resolver) in both_paths(port) {
        let reply = resolver.exchange(QUERY).await.unwrap_or_else(|e| panic!("{path}: {e}"));
        assert_eq!(reply, GOOD_REPLY, "{path}");
        let head = seen.recv().await.expect("request seen");
        assert!(
            head.starts_with("POST /proxy?targethost=target.example&targetpath=%2Fdns-query HTTP/1.1\r\n"),
            "{path}: {head}"
        );
    }
}

#[tokio::test]
async fn reply_without_content_type_is_rejected() {
    let (port, _seen) = spawn_target(GOOD_REPLY, None).await;
    for (path, resolver) in both_paths(port) {
        let error = resolver.exchange(QUERY).await.expect_err("missing Content-Type must fail");
        assert!(error.to_string().contains("Content-Type"), "{path}: {error}");
    }
}

#[tokio::test]
async fn mismatched_reply_is_rejected() {
    let (port, _seen) = spawn_target(MISMATCHED_REPLY, Some(ODOH_MESSAGE_MEDIA_TYPE)).await;
    for (path, resolver) in both_paths(port) {
        let error = resolver.exchange(QUERY).await.expect_err("mismatched answer must fail");
        assert!(
            matches!(&error, EncryptedDnsError::DnsParse(message) if message.contains("does not match the query")),
            "{path}: {error}"
        );
    }
}
