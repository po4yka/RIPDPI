//! Drives the Hysteria Realm client path against a loopback fixture that
//! plays the three server-side parts of the rendezvous as sing-box
//! `v1.14.0-alpha.22` defines them (the release the deploy role
//! `hysteria-realm` pins): a STUN server, the rendezvous service
//! (`POST /v1/{realm}/connect`, bearer token, JSON addresses + nonce + obfs
//! key), and a peer that answers obfuscated punch packets and only then
//! carries Hysteria2 QUIC on the same UDP port.
//!
//! The fixture encodes and decodes the wire format on its own, so a client
//! that disagrees with the upstream format fails here.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use local_network_fixture::Hysteria2Loopback;
use ripdpi_hysteria2::{Config, HysteriaError, RealmConfig};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};

const TOKEN: &str = "test-token";
const REALM: &str = "realm-a";
const MAGIC: &[u8; 8] = b"HYRLMv1\0";
const HELLO: u8 = 1;
const ACK: u8 = 2;

type Metadata = ([u8; 16], [u8; 32]);

#[derive(Default)]
struct Seen {
    /// Source address of the client's STUN request.
    stun_source: Option<SocketAddr>,
    request_line: Option<String>,
    addresses: Vec<String>,
    metadata: Option<Metadata>,
    /// Address whose punch packet the peer accepted.
    punched: Option<SocketAddr>,
}

struct Fixture {
    seen: Arc<Mutex<Seen>>,
    stun: SocketAddr,
    control: SocketAddr,
    _hysteria: Hysteria2Loopback,
}

fn xor(body: &mut [u8], obfs: &[u8; 32], salt: &[u8]) {
    let key: [u8; 32] = Sha256::new().chain_update(obfs).chain_update(salt).finalize().into();
    for (index, byte) in body.iter_mut().enumerate() {
        *byte ^= key[index % 32];
    }
}

fn decode_punch(packet: &[u8], (nonce, obfs): &Metadata) -> Option<u8> {
    if packet.len() < 8 + 25 {
        return None;
    }
    let mut body = packet[8..].to_vec();
    xor(&mut body, obfs, &packet[..8]);
    (&body[..8] == MAGIC && body[9..25] == *nonce).then_some(body[8])
}

fn encode_punch(kind: u8, (nonce, obfs): &Metadata) -> Vec<u8> {
    let salt = [7u8; 8];
    let mut body = [MAGIC.as_slice(), &[kind], nonce.as_slice(), &[0u8; 3]].concat();
    xor(&mut body, obfs, &salt);
    [salt.as_slice(), &body].concat()
}

fn unhex<const N: usize>(text: &str) -> [u8; N] {
    assert_eq!(text.len(), N * 2, "hex length");
    std::array::from_fn(|index| u8::from_str_radix(&text[index * 2..index * 2 + 2], 16).expect("hex digit"))
}

async fn serve_stun(socket: UdpSocket, seen: Arc<Mutex<Seen>>) {
    let mut buffer = [0u8; 1500];
    while let Ok((len, from)) = socket.recv_from(&mut buffer).await {
        let SocketAddr::V4(source) = from else { continue };
        // A binding request is exactly a 20-byte header with no attributes.
        if len != 20 || buffer[..4] != [0x00, 0x01, 0x00, 0x00] || buffer[4..8] != [0x21, 0x12, 0xa4, 0x42] {
            continue;
        }
        seen.lock().unwrap().stun_source = Some(from);
        let port = source.port() ^ 0x2112;
        let ip = u32::from(*source.ip()) ^ 0x2112_a442;
        let mut response = vec![0x01, 0x01, 0x00, 0x0c];
        response.extend_from_slice(&buffer[4..20]);
        response.extend_from_slice(&[0x00, 0x20, 0x00, 0x08, 0x00, 0x01]);
        response.extend_from_slice(&port.to_be_bytes());
        response.extend_from_slice(&ip.to_be_bytes());
        let _ = socket.send_to(&response, from).await;
    }
}

async fn serve_control(listener: TcpListener, peer: SocketAddr, seen: Arc<Mutex<Seen>>) {
    while let Ok((mut stream, _)) = listener.accept().await {
        let mut raw = Vec::new();
        let mut buffer = [0u8; 4096];
        let (head, body) = loop {
            let read = stream.read(&mut buffer).await.expect("read request");
            assert_ne!(read, 0, "request ended early");
            raw.extend_from_slice(&buffer[..read]);
            let text = String::from_utf8_lossy(&raw).into_owned();
            let Some((head, body)) = text.split_once("\r\n\r\n") else { continue };
            let length: usize = head
                .lines()
                .find_map(|line| line.strip_prefix("Content-Length: "))
                .expect("Content-Length")
                .parse()
                .expect("length");
            if body.len() >= length {
                break (head.to_owned(), body.to_owned());
            }
        };
        let authorized = head.lines().any(|line| line == format!("Authorization: Bearer {TOKEN}"));
        let (status, answer) = if authorized {
            let request: serde_json::Value = serde_json::from_str(&body).expect("JSON body");
            let nonce = request["nonce"].as_str().expect("nonce").to_owned();
            let obfs = request["obfs"].as_str().expect("obfs").to_owned();
            let mut seen = seen.lock().unwrap();
            seen.request_line = head.lines().next().map(str::to_owned);
            seen.addresses = request["addresses"]
                .as_array()
                .expect("addresses")
                .iter()
                .map(|a| a.as_str().unwrap().into())
                .collect();
            seen.metadata = Some((unhex(&nonce), unhex(&obfs)));
            ("200 OK", serde_json::json!({ "addresses": [peer.to_string()], "nonce": nonce, "obfs": obfs }))
        } else {
            ("401 Unauthorized", serde_json::json!({ "error": "invalid_token", "message": "invalid user token" }))
        };
        let answer = answer.to_string();
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{answer}",
            answer.len()
        );
        stream.write_all(response.as_bytes()).await.expect("write response");
    }
}

/// The NAT-bound Hysteria2 server: answers punch packets, and relays QUIC to
/// the Hysteria2 fixture only for the address that punched.
async fn serve_peer(socket: UdpSocket, hysteria: SocketAddr, seen: Arc<Mutex<Seen>>) {
    let mut buffer = vec![0u8; 65_535];
    while let Ok((len, from)) = socket.recv_from(&mut buffer).await {
        let (metadata, punched) = {
            let seen = seen.lock().unwrap();
            (seen.metadata, seen.punched)
        };
        if from == hysteria {
            if let Some(client) = punched {
                let _ = socket.send_to(&buffer[..len], client).await;
            }
        } else if let Some(kind) = metadata.and_then(|metadata| decode_punch(&buffer[..len], &metadata)) {
            seen.lock().unwrap().punched = Some(from);
            if kind == HELLO {
                let _ = socket.send_to(&encode_punch(ACK, &metadata.unwrap()), from).await;
            }
        } else if punched == Some(from) {
            let _ = socket.send_to(&buffer[..len], hysteria).await;
        }
    }
}

async fn start_fixture() -> Fixture {
    let hysteria = Hysteria2Loopback::start().await.expect("start hysteria2 fixture");
    let seen = Arc::new(Mutex::new(Seen::default()));
    let stun = UdpSocket::bind("127.0.0.1:0").await.expect("STUN socket");
    let peer = UdpSocket::bind("127.0.0.1:0").await.expect("peer socket");
    let control = TcpListener::bind("127.0.0.1:0").await.expect("control listener");
    let fixture = Fixture {
        seen: seen.clone(),
        stun: stun.local_addr().unwrap(),
        control: control.local_addr().unwrap(),
        _hysteria: hysteria,
    };
    let hysteria_addr = SocketAddr::from(([127, 0, 0, 1], fixture._hysteria.port()));
    let peer_addr = peer.local_addr().unwrap();
    tokio::spawn(serve_stun(stun, seen.clone()));
    tokio::spawn(serve_control(control, peer_addr, seen.clone()));
    tokio::spawn(serve_peer(peer, hysteria_addr, seen));
    fixture
}

fn realm_config(fixture: &Fixture, token: &str) -> Config {
    // Port 9 (discard) answers nothing: a client that dials the configured
    // server address instead of the punched peer cannot pass.
    let mut config = Config::from_parts("test".into(), "127.0.0.1", 9, "localhost".into()).expect("config");
    config.insecure = true;
    config.realm = Some(RealmConfig {
        server_url: format!("http://{}", fixture.control),
        token: token.into(),
        realm_id: REALM.into(),
        stun_servers: vec![fixture.stun.to_string()],
    });
    config
}

#[tokio::test]
async fn realm_client_discovers_registers_punches_and_proxies_through_the_peer() {
    let fixture = start_fixture().await;

    let client = ripdpi_hysteria2::connect(&realm_config(&fixture, TOKEN)).await.expect("realm connect");
    let mut stream = client.tcp_connect("127.0.0.1:9").await.expect("open proxy stream");
    stream.write_all(b"through-the-realm").await.expect("write");
    stream.flush().await.expect("flush");
    let mut echoed = [0u8; 17];
    stream.read_exact(&mut echoed).await.expect("read echo");
    assert_eq!(&echoed, b"through-the-realm");

    let seen = fixture.seen.lock().unwrap();
    let client_addr = seen.stun_source.expect("the client asked the STUN server");
    assert_eq!(seen.request_line.as_deref(), Some("POST /v1/realm-a/connect HTTP/1.1"));
    assert_eq!(seen.addresses, [client_addr.to_string()], "the client registers its STUN-mapped address");
    assert_eq!(seen.punched, Some(client_addr), "QUIC runs on the socket that did STUN and the punch");
}

#[tokio::test]
async fn realm_client_reports_a_rejected_token_as_a_rendezvous_failure() {
    let fixture = start_fixture().await;

    let Err(error) = ripdpi_hysteria2::connect(&realm_config(&fixture, "wrong-token")).await else {
        panic!("a rejected token must not connect");
    };

    assert!(matches!(&error, HysteriaError::RealmControl(reason) if reason == "status 401 invalid_token"), "{error}");
    assert_eq!(fixture.seen.lock().unwrap().punched, None);
}
