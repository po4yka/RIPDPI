use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use super::*;
use crate::{cancel_http_request, create_http_request, execute_http_request, release_http_request};

fn request(listener: &TcpListener) -> String {
    json!({
        "url": format!("http://{}/catalog", listener.local_addr().expect("address")),
        "callTimeoutMs": 5000,
    })
    .to_string()
}

#[test]
fn precancelled_http_request_opens_no_socket() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback");
    listener.set_nonblocking(true).expect("nonblocking");
    let handle = create_http_request();
    cancel_http_request(handle);
    let payload: Value =
        serde_json::from_str(&execute_http_request(handle, &request(&listener)).expect("response")).expect("JSON");
    release_http_request(handle);
    assert_eq!(payload["cancelled"], true);
    assert!(payload["bodyBase64"].is_null());
    assert_eq!(listener.accept().expect_err("no I/O after pre-cancel").kind(), io::ErrorKind::WouldBlock);
    assert!(subscribe(handle).is_err());
}

#[test]
fn cancellation_stops_a_stalled_http_body_and_releases_the_socket() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback");
    let request = request(&listener);
    let destination = TcpListener::bind("127.0.0.1:0").expect("redirect target");
    destination.set_nonblocking(true).expect("nonblocking target");
    let handle = create_http_request();
    let worker = thread::spawn(move || {
        let result = execute_http_request(handle, &request);
        release_http_request(handle);
        result
    });
    let (mut peer, _) = listener.accept().expect("HTTP connection");
    peer.set_read_timeout(Some(Duration::from_secs(1))).expect("read timeout");
    let mut received = [0u8; 2048];
    let count = peer.read(&mut received).expect("HTTP request");
    assert!(received[..count].starts_with(b"GET /catalog HTTP/1.1\r\n"));
    write!(
        peer,
        "HTTP/1.1 302 Found\r\nLocation: http://{}/next\r\nContent-Length: 100\r\n\r\nprefix",
        destination.local_addr().expect("redirect address"),
    )
    .expect("stalled redirect body");
    let started = Instant::now();
    cancel_http_request(handle);
    match peer.read(&mut received) {
        Ok(0) => {}
        Err(error) if error.kind() == io::ErrorKind::ConnectionReset => {}
        result => panic!("socket remains open after cancellation: {result:?}"),
    }
    let payload: Value = serde_json::from_str(&worker.join().expect("worker").expect("response")).expect("JSON");
    assert!(started.elapsed() < Duration::from_secs(1));
    assert_eq!(payload["cancelled"], true);
    assert!(payload["statusCode"].is_null());
    assert!(payload["bodyBase64"].is_null());
    assert!(subscribe(handle).is_err());
    assert_eq!(destination.accept().expect_err("no redirect after cancellation").kind(), io::ErrorKind::WouldBlock);

    let fresh = create_http_request();
    cancel_http_request(handle);
    assert_ne!(fresh, handle);
    assert!(!*subscribe(fresh).expect("new request").borrow());
    release_http_request(fresh);
}

#[test]
fn unknown_or_released_http_handles_are_rejected_and_idempotent() {
    for raw in [0, -1, i64::MIN] {
        let handle = HttpRequestId::from_jni(raw);
        assert_eq!(handle.as_jni(), raw);
        assert_eq!(subscribe(handle).expect_err("unknown request").kind(), io::ErrorKind::InvalidInput);
        cancel_http_request(handle);
        release_http_request(handle);
    }
    let handle = create_http_request();
    release_http_request(handle);
    release_http_request(handle);
    assert!(execute_http_request(handle, "{}").is_err());
}

#[test]
fn json_command_lifecycle_uses_the_existing_fetch_entry() {
    let created: Value =
        serde_json::from_str(&crate::execute(r#"{"operation":"create"}"#).expect("reserve")).expect("reservation");
    let handle = created["requestHandle"].as_i64().expect("request ID");
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback");
    listener.set_nonblocking(true).expect("nonblocking");
    crate::execute(&json!({"operation":"cancel", "requestHandle":handle}).to_string()).expect("cancel");
    let response: Value = serde_json::from_str(
        &crate::execute(
            &json!({"operation":"execute", "requestHandle":handle, "requestJson":request(&listener)}).to_string(),
        )
        .expect("execute"),
    )
    .expect("response");
    crate::execute(&json!({"operation":"release", "requestHandle":handle}).to_string()).expect("release");
    assert_eq!(response["cancelled"], true);
    assert_eq!(listener.accept().expect_err("pre-cancel must avoid I/O").kind(), io::ErrorKind::WouldBlock);
    assert!(subscribe(HttpRequestId::from_jni(handle)).is_err());
}
