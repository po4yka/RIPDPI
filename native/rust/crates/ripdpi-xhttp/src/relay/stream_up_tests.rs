use std::convert::Infallible;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{Notify, Semaphore, mpsc};

use crate::config::{XhttpMode, XhttpTlsConfig};
use crate::h2_body::{ChannelBody, XhttpBody};
use crate::pool::PooledConnection;

fn mode() -> XhttpMode {
    XhttpMode::Tls(
        XhttpTlsConfig::from_strings(
            "127.0.0.1",
            443,
            "peer.test",
            "550e8400-e29b-41d4-a716-446655440000",
            "/xhttp",
            "",
            "chrome_stable",
        )
        .expect("TLS config"),
    )
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns and aborts both local HTTP/2 drivers after the exchange.
async fn exchange_before_upload_response_headers() {
    const PAYLOAD: &[u8] = b"application payload before POST headers";
    let mode = mode();
    let request = mode.tcp_request("127.0.0.1:443").expect("VLESS request");
    let request_len = request.len();
    let (client_io, server_io) = tokio::io::duplex(64 * 1024);
    let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
        .await
        .expect("client H2 handshake");
    let driver = tokio::spawn(async move { connection.await.expect("client H2 driver") });
    let (download_tx, download_rx) = mpsc::channel(8);
    let download = Arc::new(Mutex::new(Some(download_rx)));
    let server = tokio::spawn(async move {
        let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
            let download = Arc::clone(&download);
            let download_tx = download_tx.clone();
            async move {
                let response: XhttpBody = if request.method() == hyper::Method::GET {
                    ChannelBody::new(download.lock().expect("download lock").take().expect("download GET")).boxed()
                } else {
                    let mut body = request.into_body();
                    let mut uploaded = Vec::new();
                    while uploaded.len() < request_len + PAYLOAD.len() {
                        let frame = body.frame().await.expect("upload data").expect("valid upload");
                        if let Ok(data) = frame.into_data() {
                            uploaded.extend_from_slice(&data);
                        }
                    }
                    assert_eq!(&uploaded[request_len..], PAYLOAD);
                    let mut response = vec![0, 0];
                    response.extend_from_slice(PAYLOAD);
                    download_tx.send(Ok(Bytes::from(response))).await.expect("download payload");
                    Full::new(Bytes::new()).map_err(|never| match never {}).boxed()
                };
                Ok::<_, Infallible>(hyper::Response::new(response))
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(server_io), service)
            .await;
    });
    let pooled = Arc::new(PooledConnection::new(sender, 1, Arc::new(Notify::new())));
    let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("stream capacity");
    assert!(pooled.opening_started());
    let mut stream =
        tokio::time::timeout(Duration::from_millis(500), pooled.open_stream_from_mode(&mode, &request, permit))
            .await
            .expect("stream-up must open without POST response headers")
            .expect("stream-up opened");
    stream.write_all(PAYLOAD).await.expect("write application payload");
    let mut response = vec![0; PAYLOAD.len()];
    tokio::time::timeout(Duration::from_secs(1), stream.read_exact(&mut response))
        .await
        .expect("download deadline")
        .expect("download payload");
    assert_eq!(response, PAYLOAD);
    drop(stream);
    drop(pooled);
    for task in [driver, server] {
        task.abort();
        match task.await {
            Ok(()) => {}
            Err(error) => assert!(error.is_cancelled()),
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: inherits the test-owned HTTP/2 drivers in the exchange helper.
#[tokio::test]
async fn stream_up_does_not_wait_for_upload_response_headers() {
    exchange_before_upload_response_headers().await;
}

/// # Cancel safety:
/// NOT cancel-safe: inherits the test-owned HTTP/2 drivers in the exchange helper.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stream_up_does_not_wait_for_upload_response_headers_multithread() {
    exchange_before_upload_response_headers().await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns and aborts the local H2 and body-holder tasks.
async fn observe_late_upload_failure(reset_after_headers: bool) {
    let mode = mode();
    let request = mode.tcp_request("127.0.0.1:443").expect("VLESS request");
    let (client_io, server_io) = tokio::io::duplex(64 * 1024);
    let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
        .await
        .expect("client H2 handshake");
    let driver = tokio::spawn(async move { connection.await.expect("client H2 driver") });
    let (download_tx, download_rx) = mpsc::channel(8);
    download_tx.send(Ok(Bytes::from_static(&[0, 0]))).await.expect("VLESS response header");
    let download = Arc::new(Mutex::new(Some(download_rx)));
    let (control_tx, mut control_rx) = mpsc::unbounded_channel();
    let body_tasks = Arc::new(Mutex::new(Vec::new()));
    let server_body_tasks = Arc::clone(&body_tasks);
    let server = tokio::spawn(async move {
        let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
            let download = Arc::clone(&download);
            let control_tx = control_tx.clone();
            let tasks = Arc::clone(&server_body_tasks);
            async move {
                if request.method() == hyper::Method::GET {
                    let body = ChannelBody::new(download.lock().expect("download lock").take().expect("GET body"));
                    return Ok::<_, Infallible>(hyper::Response::new(body.boxed()));
                }
                let mut upload = request.into_body();
                upload.frame().await.expect("VLESS request").expect("valid upload frame");
                tasks.lock().expect("body tasks").push(tokio::spawn(async move {
                    let _upload = upload;
                    std::future::pending::<()>().await;
                }));
                let (release_tx, release_rx) = tokio::sync::oneshot::channel();
                control_tx.send(release_tx).expect("POST control");
                let (response_tx, response_rx) = mpsc::channel(1);
                let mut response = hyper::Response::new(ChannelBody::new(response_rx).boxed());
                if reset_after_headers {
                    tasks.lock().expect("body tasks").push(tokio::spawn(async move {
                        release_rx.await.expect("release reset");
                        response_tx.send(Err(std::io::Error::other("fixture upload reset"))).await.expect("reset body");
                    }));
                } else {
                    release_rx.await.expect("release rejection");
                    *response.status_mut() = hyper::StatusCode::FORBIDDEN;
                }
                Ok(response)
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(server_io), service)
            .await;
    });
    let pooled = Arc::new(PooledConnection::new(sender, 1, Arc::new(Notify::new())));
    let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("stream capacity");
    assert!(pooled.opening_started());
    let stream =
        tokio::time::timeout(Duration::from_millis(500), pooled.open_stream_from_mode(&mode, &request, permit))
            .await
            .expect("stream opens before POST response")
            .expect("open stream-up");
    let release = tokio::time::timeout(Duration::from_secs(1), control_rx.recv())
        .await
        .expect("POST received")
        .expect("POST release");
    let (mut read, mut write) = tokio::io::split(stream);
    let mut reading = tokio::spawn(async move { read.read_u8().await });
    assert!(tokio::time::timeout(Duration::from_millis(30), &mut reading).await.is_err());
    release.send(()).expect("release upload failure");
    let error = tokio::time::timeout(Duration::from_secs(1), reading)
        .await
        .expect("upload failure wakes pending read")
        .expect("read task")
        .expect_err("upload failure must not become EOF or hang");
    assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
    let error = write.write_all(b"after upload failure").await.expect_err("writes must observe upload failure");
    assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
    drop(write);
    drop(download_tx);
    drop(pooled);
    for task in [driver, server] {
        task.abort();
        match task.await {
            Ok(()) => {}
            Err(error) => assert!(error.is_cancelled()),
        }
    }
    let tasks = std::mem::take(&mut *body_tasks.lock().expect("body tasks"));
    for task in tasks {
        task.abort();
        match task.await {
            Ok(()) => {}
            Err(error) => assert!(error.is_cancelled()),
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: inherits the test-owned H2 and body-holder tasks.
#[tokio::test]
async fn stream_up_late_rejection_or_reset_wakes_read_and_fails_writes() {
    for reset in [false, true] {
        observe_late_upload_failure(reset).await;
    }
}

/// # Cancel safety:
/// NOT cancel-safe: inherits the test-owned H2 and body-holder tasks.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stream_up_late_rejection_or_reset_wakes_read_and_fails_writes_multithread() {
    for reset in [false, true] {
        observe_late_upload_failure(reset).await;
    }
}
