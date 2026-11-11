use std::io::{self, Cursor, Read, Write};
use std::net::TcpListener;
use std::thread;
use std::time::{Duration, Instant};

use std::sync::{Arc, Mutex};
use tungstenite::{Message, WebSocket, protocol::Role};

use ripdpi_ws_tunnel::{WsTransportConfig, build_ws_request};

#[derive(Clone)]
struct Capture(Arc<Mutex<Vec<u8>>>);

impl Write for Capture {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.0.lock().expect("capture lock").extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[test]
fn websocket_logs_keep_credentials_and_payload_on_wire_only() {
    let capture = Capture(Arc::new(Mutex::new(Vec::new())));
    let writer = capture.clone();
    tracing_subscriber::fmt()
        .without_time()
        .with_ansi(false)
        .with_max_level(tracing::Level::TRACE)
        .with_writer(move || writer.clone())
        .try_init()
        .expect("install tracing and log bridge");

    let mut request =
        build_ws_request(&WsTransportConfig::new("localhost", "/stream?credential=fixture-query-material"), false)
            .expect("request");
    request.headers_mut().insert("Authorization", "Bearer fixture-auth-material".parse().expect("fixture auth"));
    request
        .headers_mut()
        .insert("Sec-WebSocket-Protocol", "fixture-early-data-material".parse().expect("fixture data"));
    let (wire, _) = tungstenite::handshake::client::generate_request(request).expect("serialize request");
    let wire = String::from_utf8(wire).expect("ASCII request");
    // Literal RFC 6455 unmasked server text and Close frames. No peer codec creates them.
    let inbound = "fixture-inbound-data";
    let mut inbound_frame = vec![0x81, u8::try_from(inbound.len()).expect("short text")];
    inbound_frame.extend_from_slice(inbound.as_bytes());
    let mut client = WebSocket::from_raw_socket(Cursor::new(inbound_frame), Role::Client, None);
    assert_eq!(client.read().expect("read text").into_text().expect("text"), inbound);

    let outbound = "fixture-outbound-data";
    let mut server = WebSocket::from_raw_socket(Cursor::new(Vec::new()), Role::Server, None);
    server.send(Message::Text(outbound.into())).expect("send text");
    assert!(server.get_ref().get_ref().windows(outbound.len()).any(|value| value == outbound.as_bytes()));

    let reason = "fixture-close-reason";
    let mut close_frame = vec![0x88, u8::try_from(2 + reason.len()).expect("short reason"), 0x03, 0xe8];
    close_frame.extend_from_slice(reason.as_bytes());
    let mut client = WebSocket::from_raw_socket(Cursor::new(close_frame), Role::Client, None);
    let Message::Close(Some(close)) = client.read().expect("read Close") else { panic!("expected Close") };
    assert_eq!(close.reason, reason);
    client.flush().expect("flush Close reply");

    let listener = TcpListener::bind("127.0.0.1:0").expect("bind redirect peer");
    listener.set_nonblocking(true).expect("nonblocking listener");
    let address = listener.local_addr().expect("peer address");
    let peer = thread::spawn(move || {
        for (index, marker) in ["fixture-connect-material", "fixture-redirect-material"].into_iter().enumerate() {
            let deadline = Instant::now() + Duration::from_secs(5);
            let mut stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(1));
                    }
                    Err(error) => panic!("accept redirect peer: {error}"),
                }
            };
            stream.set_nonblocking(false).expect("blocking peer reads");
            stream.set_read_timeout(Some(Duration::from_secs(5))).expect("read timeout");
            let mut head = Vec::new();
            while !head.ends_with(b"\r\n\r\n") {
                let mut byte = [0];
                stream.read_exact(&mut byte).expect("request byte");
                head.push(byte[0]);
                assert!(head.len() < 8192);
            }
            assert!(String::from_utf8(head).expect("ASCII request").contains(marker));
            let response = if index == 0 {
                format!(
                    "HTTP/1.1 302 Found\r\nLocation: ws://{address}/fixture-redirect-material\r\nContent-Length: 0\r\n\r\n"
                )
            } else {
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".to_owned()
            };
            stream.write_all(response.as_bytes()).expect("write response");
        }
    });
    assert!(tungstenite::connect(format!("ws://{address}/fixture-connect-material")).is_err());
    peer.join().expect("redirect peer joins");

    let logs = String::from_utf8(capture.0.lock().expect("capture lock").clone()).expect("UTF-8 logs");
    assert!(!logs.is_empty(), "trace capture must observe the real serializer");
    for value in ["fixture-query-material", "fixture-auth-material", "fixture-early-data-material"] {
        assert!(wire.contains(value), "wire credential lost: {value}");
    }
    for value in [
        "fixture-query-material",
        "fixture-auth-material",
        "fixture-early-data-material",
        inbound,
        outbound,
        reason,
        "fixture-connect-material",
        "fixture-redirect-material",
    ] {
        let hex = value.as_bytes().iter().map(|byte| format!("{byte:02x}")).collect::<String>();
        assert!(!logs.contains(value), "log disclosed wire data: {value}");
        assert!(!logs.to_ascii_lowercase().contains(&hex), "log disclosed hex wire data: {value}");
    }
}
