use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use std::time::Duration;

use bytes::Bytes;
use http::{Request, StatusCode};
use http_body_util::{BodyExt, Empty};
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;

use super::{OriginConfig, OriginServer};

/// # Cancel safety:
/// NOT cancel-safe: cancellation can leave test driver tasks running; the caller
/// must discard the test runtime.
#[tokio::test]
async fn configured_wire_paths_match_client_url_semantics() {
    // Independent Go URL.Path vectors, also asserted by the native client tests.
    for (configured, wire, expected) in [
        ("/literal%3Fpath?token=a/", "/literal%253Fpath/sid?token=a/", StatusCode::OK),
        ("/p//", "/p//sid", StatusCode::OK),
        ("/a b/é#?q=x/", "/a%20b/%C3%A9%23/sid?q=x/", StatusCode::OK),
        ("/p", "/p/s%69d", StatusCode::OK),
        ("/literal?token=x", "/literal%3Ftoken=x/sid", StatusCode::NOT_FOUND),
        ("/p", "/p/%", StatusCode::NOT_FOUND),
        ("/p", "/p/%GG", StatusCode::NOT_FOUND),
        ("/p", "/p/%FF", StatusCode::NOT_FOUND),
        ("/p", "/p/sid%2Fextra", StatusCode::NOT_FOUND),
        ("/p", "/p/sid/", StatusCode::NOT_FOUND),
        ("/p", "/prefix/sid", StatusCode::NOT_FOUND),
        ("/", "//sid", StatusCode::NOT_FOUND),
        ("/p//", "/p/sid", StatusCode::NOT_FOUND),
    ] {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("origin listener");
        let address = listener.local_addr().expect("origin address");
        let origin = OriginServer {
            config: Arc::new(OriginConfig {
                listen: address.to_string(),
                path: crate::path::normalize_path(configured),
                uuid: [0; 16],
                protect_path: None,
            }),
            target_allowed: |_| false,
            sessions: Arc::new(Mutex::new(HashMap::new())),
            next_session_seq: Arc::new(AtomicU64::new(0)),
        };
        let sessions = Arc::clone(&origin.sessions);
        let server = tokio::spawn(async move {
            let (socket, _) = listener.accept().await.expect("accept HTTP client");
            let service = service_fn(move |request| origin.clone().handle_request(request));
            hyper::server::conn::http1::Builder::new().serve_connection(TokioIo::new(socket), service).await
        });
        let socket = TcpStream::connect(address).await.expect("HTTP socket");
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(socket)).await.expect("HTTP handshake");
        let driver = tokio::spawn(connection);
        let request = Request::builder()
            .uri(wire)
            .header("Host", "origin.test")
            .body(Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
            .expect("HTTP request");
        let response = tokio::time::timeout(Duration::from_secs(2), sender.send_request(request))
            .await
            .expect("HTTP response deadline")
            .expect("HTTP response");
        assert_eq!(response.status(), expected, "configured={configured:?}, wire={wire:?}");
        if expected == StatusCode::OK {
            assert!(sessions.lock().await.contains_key("sid"), "decoded session must be paired by its exact ID");
        } else {
            assert!(sessions.lock().await.is_empty(), "invalid path must not allocate a session");
        }
        drop(response);
        drop(sender);
        driver.abort();
        let _ = driver.await;
        server.abort();
        let _ = server.await;
    }
}
