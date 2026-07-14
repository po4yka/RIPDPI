use std::io::{self, Read, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use bytes::Bytes;
use http::StatusCode;
use http::header::{HeaderValue, LOCATION};
use ripdpi_runtime_platform::protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};
use serde_json::Value;
use url::Url;

use super::dto::RawHttpResponse;
use super::execute;
use super::redirect::redirect_target;
use super::request::MAX_RESPONSE_BODY_BYTES;

static PROTECT_TEST_MUTEX: Mutex<()> = Mutex::new(());

#[test]
fn execute_fetches_plain_http_response() {
    let server = spawn_http_server(vec![http_response("200 OK", &[], b"manifest")]);
    let port = server.local_addr().expect("local addr").port();
    let request = serde_json::json!({
        "url": format!("http://127.0.0.1:{port}/manifest.json"),
        "headers": {"User-Agent": "RIPDPI test"},
        "tlsProfileId": "chrome_stable",
    });

    let payload = execute(&request.to_string()).expect("execute");
    let response: Value = serde_json::from_str(&payload).expect("json response");

    assert_eq!(response["statusCode"], 200);
    assert_eq!(STANDARD.decode(response["bodyBase64"].as_str().expect("body")).expect("decode body"), b"manifest");
    assert_eq!(response["finalUrl"].as_str().expect("final url"), format!("http://127.0.0.1:{port}/manifest.json"));
    assert_eq!(response["tlsProfileId"], "chrome_stable");
    assert_eq!(response["tlsProfileCatalogVersion"], "v1");
    assert_eq!(response["tlsBrowserFamily"], "chrome");
    assert_eq!(response["tlsBrowserTrack"], "android-stable");
    assert_eq!(response["tlsTemplateAlpn"], "h2_http11");
    assert_eq!(response["clientHelloInvariantStatus"], "avoids_blocked_517_byte_client_hello");
}

#[test]
fn execute_rejects_oversized_response_before_reading_body() {
    let response = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n", MAX_RESPONSE_BODY_BYTES + 1).into_bytes();
    let server = spawn_http_server(vec![response]);
    let port = server.local_addr().expect("local addr").port();
    let request = serde_json::json!({
        "url": format!("http://127.0.0.1:{port}/oversized.bin"),
        "tlsProfileId": "chrome_stable",
    });

    let payload = execute(&request.to_string()).expect("execute");
    let response: Value = serde_json::from_str(&payload).expect("json response");

    assert_eq!(response["bodyBase64"], Value::Null);
    assert!(response["error"].as_str().expect("error").contains(&format!("exceeds {MAX_RESPONSE_BODY_BYTES} bytes")));
}

#[test]
fn execute_rejects_unsupported_scheme_before_connect() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    listener.set_nonblocking(true).expect("set nonblocking");
    let port = listener.local_addr().expect("local addr").port();
    let request = serde_json::json!({
        "url": format!("ftp://127.0.0.1:{port}/manifest.json"),
        "tlsProfileId": "chrome_stable",
    });

    let payload = execute(&request.to_string()).expect("execute");
    let response: Value = serde_json::from_str(&payload).expect("json response");

    assert!(response["error"].as_str().expect("error").contains("unsupported scheme"));
    let accept_error = listener.accept().expect_err("unsupported scheme must not open a connection");
    assert_eq!(accept_error.kind(), io::ErrorKind::WouldBlock);
}

#[test]
fn execute_follows_redirects() {
    let server = spawn_http_server(vec![
        http_response("302 Found", &[("Location", "/final.json")], b""),
        http_response("200 OK", &[], b"catalog"),
    ]);
    let port = server.local_addr().expect("local addr").port();
    let request = serde_json::json!({
        "url": format!("http://127.0.0.1:{port}/manifest.json"),
        "headers": {"User-Agent": "RIPDPI test"},
        "tlsProfileId": "chrome_stable",
        "maxRedirects": 2,
    });

    let payload = execute(&request.to_string()).expect("execute");
    let response: Value = serde_json::from_str(&payload).expect("json response");

    assert_eq!(response["statusCode"], 200);
    assert_eq!(STANDARD.decode(response["bodyBase64"].as_str().expect("body")).expect("decode body"), b"catalog");
    assert_eq!(response["finalUrl"].as_str().expect("final url"), format!("http://127.0.0.1:{port}/final.json"));
    assert_eq!(response["tlsJa3ParityTarget"], "chrome-stable");
    assert_eq!(response["tlsJa4ParityTarget"], "chrome-stable");
    assert_eq!(response["tlsTemplateGreaseStyle"], "chromium_single_grease");
}

#[test]
fn redirect_target_rejects_https_to_http_downgrade() {
    let current_url = Url::parse("https://example.com/start.json").expect("current url");
    let response = RawHttpResponse {
        status_code: StatusCode::FOUND,
        headers: http::HeaderMap::from_iter([(LOCATION, HeaderValue::from_static("http://example.com/insecure.json"))]),
        body: Bytes::new(),
    };

    let error = redirect_target(&current_url, &response).expect_err("downgrade should be rejected");

    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    assert!(error.to_string().contains("HTTPS to HTTP redirect downgrade"));
}

#[test]
fn execute_protects_socket_before_connect_when_callback_registered() {
    let _lock = PROTECT_TEST_MUTEX.lock().expect("test mutex");
    let _guard = ProtectRegistrationGuard::register();
    let callback = Arc::new(TestProtectCallback::default());
    let callback_for_registration: Arc<dyn ProtectCallback> = callback.clone();
    ProtectRegistrationGuard::install(callback_for_registration);

    let server = spawn_http_server(vec![http_response("200 OK", &[], b"manifest")]);
    let port = server.local_addr().expect("local addr").port();
    let request = serde_json::json!({
        "url": format!("http://127.0.0.1:{port}/manifest.json"),
        "headers": {"User-Agent": "RIPDPI test"},
        "tlsProfileId": "chrome_stable",
    });

    let payload = execute(&request.to_string()).expect("execute");
    let response: Value = serde_json::from_str(&payload).expect("json response");

    assert_eq!(response["statusCode"], 200);
    assert!(callback.last_fd.load(Ordering::Relaxed) >= 0, "protect callback should observe a socket fd");
}

#[derive(Default)]
struct TestProtectCallback {
    last_fd: AtomicI32,
}

impl ProtectCallback for TestProtectCallback {
    fn protect(&self, fd: std::os::fd::RawFd) -> io::Result<()> {
        self.last_fd.store(fd, Ordering::Relaxed);
        Ok(())
    }
}

struct ProtectRegistrationGuard;

impl ProtectRegistrationGuard {
    fn register() -> Self {
        unregister_protect_callback();
        Self
    }

    fn install(callback: Arc<dyn ProtectCallback>) {
        register_protect_callback(callback);
    }
}

impl Drop for ProtectRegistrationGuard {
    fn drop(&mut self) {
        unregister_protect_callback();
    }
}

fn http_response(status_line: &str, headers: &[(&str, &str)], body: &[u8]) -> Vec<u8> {
    let mut response = format!("HTTP/1.1 {status_line}\r\nContent-Length: {}\r\n", body.len());
    for (name, value) in headers {
        response.push_str(&format!("{name}: {value}\r\n"));
    }
    response.push_str("\r\n");
    let mut bytes = response.into_bytes();
    bytes.extend_from_slice(body);
    bytes
}

fn spawn_http_server(responses: Vec<Vec<u8>>) -> TcpListener {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    let server = listener.try_clone().expect("clone listener");
    thread::spawn(move || {
        for response in responses {
            let (mut stream, _) = server.accept().expect("accept");
            let mut buffer = [0u8; 1024];
            let _ = stream.read(&mut buffer);
            stream.write_all(&response).expect("write response");
            stream.flush().expect("flush response");
        }
    });
    listener
}
