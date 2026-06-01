use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use ripdpi_webtunnel::http_upgrade::{HttpUpgradeRequest, perform_http_upgrade};

#[test]
fn http_upgrade_request_matches_go_fixture_and_preserves_tunnel_bytes() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind fixture listener");
    let addr = listener.local_addr().expect("fixture addr");
    let server = thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept client");
        socket.set_read_timeout(Some(Duration::from_secs(5))).expect("server read timeout");
        let request = read_until_headers(&mut socket);
        assert_eq!(
            request,
            concat!(
                "GET /secret-path HTTP/1.1\r\n",
                "Host: front.example\r\n",
                "Connection: upgrade\r\n",
                "Upgrade: websocket\r\n",
                "\r\n"
            )
        );
        socket
            .write_all(
                b"HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\nserver-ready",
            )
            .expect("write upgrade response");
        let mut client_payload = [0_u8; 11];
        socket.read_exact(&mut client_payload).expect("read tunneled client payload");
        assert_eq!(&client_payload, b"client-data");
        let _ = socket.shutdown(Shutdown::Both);
    });

    let stream = TcpStream::connect(addr).expect("connect fixture");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("client read timeout");
    stream.set_write_timeout(Some(Duration::from_secs(5))).expect("client write timeout");
    let mut upgraded = perform_http_upgrade(
        stream,
        &HttpUpgradeRequest { host: "front.example".to_owned(), secret_path: "/secret-path".to_owned() },
    )
    .expect("upgrade succeeds");
    let mut server_ready = [0_u8; 12];
    upgraded.read_exact(&mut server_ready).expect("read post-upgrade bytes");
    assert_eq!(&server_ready, b"server-ready");
    upgraded.write_all(b"client-data").expect("write tunneled client payload");
    server.join().expect("server join");
}

#[test]
fn http_upgrade_rejects_non_switching_protocols() {
    assert_upgrade_error(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n");
}

#[test]
fn http_upgrade_rejects_missing_upgrade_headers() {
    assert_upgrade_error(b"HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\n\r\n");
    assert_upgrade_error(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n");
}

fn assert_upgrade_error(response: &'static [u8]) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind fixture listener");
    let addr = listener.local_addr().expect("fixture addr");
    let server = thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept client");
        let _ = read_until_headers(&mut socket);
        socket.write_all(response).expect("write response");
    });
    let stream = TcpStream::connect(addr).expect("connect fixture");
    let result = perform_http_upgrade(
        stream,
        &HttpUpgradeRequest { host: "front.example".to_owned(), secret_path: "/secret".to_owned() },
    );
    assert!(result.is_err(), "upgrade should reject invalid response");
    server.join().expect("server join");
}

fn read_until_headers(stream: &mut TcpStream) -> String {
    let mut bytes = Vec::new();
    let mut byte = [0_u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        stream.read_exact(&mut byte).expect("read request byte");
        bytes.push(byte[0]);
    }
    String::from_utf8(bytes).expect("request utf8")
}
