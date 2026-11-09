//! Wire-level refusal checks for RFC 10024 hybrid server shares.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use boring::ssl::SslVersion;

const HYBRID_GROUP: u16 = 0x11ec;
const X25519_GROUP: u16 = 0x001d;

#[test]
fn invalid_hybrid_share_lengths_send_illegal_parameter() {
    for length in [1119, 1121] {
        assert_server_share_alert(HYBRID_GROUP, &vec![0; length], false, 47);
    }
}

#[test]
fn hybrid_zero_x25519_sends_illegal_parameter() {
    assert_server_share_alert(HYBRID_GROUP, &[0; 1120], false, 47);
}

#[test]
fn classical_zero_x25519_sends_illegal_parameter() {
    assert_server_share_alert(X25519_GROUP, &[0; 32], false, 47);
}

#[test]
fn unoffered_group_sends_illegal_parameter() {
    assert_server_share_alert(0x6399, &[0; 1120], false, 47);
}

#[test]
fn truncated_share_vector_sends_decode_error() {
    assert_server_share_alert(HYBRID_GROUP, &[0; 1120], true, 50);
}

fn assert_server_share_alert(group: u16, share: &[u8], truncated: bool, description: u8) {
    let mut builder = ripdpi_tls_profiles::configure_builder("chrome_stable").expect("TLS profile");
    builder.set_min_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 minimum");
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind TLS peer");
    let addr = listener.local_addr().expect("TLS peer address");
    let share = share.to_vec();
    let peer = thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept TLS client");
        socket.set_read_timeout(Some(Duration::from_secs(5))).expect("peer read timeout");
        socket.set_write_timeout(Some(Duration::from_secs(5))).expect("peer write timeout");
        let (content_type, hello) = read_record(&mut socket);
        assert_eq!(content_type, 22, "client handshake record");
        assert_eq!(hello[0], 1, "ClientHello handshake");
        // RFC 9846: handshake header, legacy_version, random, session-id vector.
        let session_id_len = usize::from(hello[38]);
        let session_id = hello.get(39..39 + session_id_len).expect("ClientHello session ID");
        socket.write_all(&server_hello(session_id, group, &share, truncated)).expect("send ServerHello");
        loop {
            let (content_type, payload) = read_record(&mut socket);
            if content_type == 20 {
                assert_eq!(payload, [1], "compatibility ChangeCipherSpec");
                continue;
            }
            assert_eq!(content_type, 21, "client must send an alert before any application data");
            assert_eq!(payload.len(), 2, "plaintext TLS alert");
            return [payload[0], payload[1]];
        }
    });

    let socket = TcpStream::connect(addr).expect("connect TLS peer");
    socket.set_read_timeout(Some(Duration::from_secs(5))).expect("client read timeout");
    socket.set_write_timeout(Some(Duration::from_secs(5))).expect("client write timeout");
    let result = builder.build().connect("pqkem.example", socket);
    let alert = peer.join().expect("TLS peer worker");
    assert!(result.is_err(), "invalid server share must not establish TLS");
    assert_eq!(alert, [2, description], "fatal alert for group {group:#06x}");
}

fn read_record(socket: &mut TcpStream) -> (u8, Vec<u8>) {
    let mut header = [0; 5];
    socket.read_exact(&mut header).expect("TLS record header");
    let length = usize::from(u16::from_be_bytes([header[3], header[4]]));
    assert!(length <= 16_384, "TLS plaintext record limit");
    let mut payload = vec![0; length];
    socket.read_exact(&mut payload).expect("TLS record payload");
    (header[0], payload)
}

fn server_hello(session_id: &[u8], group: u16, share: &[u8], truncated: bool) -> Vec<u8> {
    // This peer serializes the public TLS structures directly. It uses no
    // RIPDPI parser or KEM implementation to decide the expected alert.
    let mut key_share = group.to_be_bytes().to_vec();
    let declared_length = u16::try_from(share.len() + usize::from(truncated)).expect("share length");
    key_share.extend_from_slice(&declared_length.to_be_bytes());
    key_share.extend_from_slice(share);

    let mut extensions = vec![0, 43, 0, 2, 3, 4, 0, 51];
    extensions.extend_from_slice(&u16::try_from(key_share.len()).expect("key_share length").to_be_bytes());
    extensions.extend_from_slice(&key_share);

    let mut body = vec![3, 3];
    body.extend_from_slice(&[0x42; 32]);
    body.push(u8::try_from(session_id.len()).expect("session ID length"));
    body.extend_from_slice(session_id);
    body.extend_from_slice(&[0x13, 0x01, 0]);
    body.extend_from_slice(&u16::try_from(extensions.len()).expect("extensions length").to_be_bytes());
    body.extend_from_slice(&extensions);

    let mut handshake = vec![2];
    handshake.extend_from_slice(&u32::try_from(body.len()).expect("handshake length").to_be_bytes()[1..]);
    handshake.extend_from_slice(&body);
    let mut record = vec![22, 3, 3];
    record.extend_from_slice(&u16::try_from(handshake.len()).expect("record length").to_be_bytes());
    record.extend_from_slice(&handshake);
    record
}
