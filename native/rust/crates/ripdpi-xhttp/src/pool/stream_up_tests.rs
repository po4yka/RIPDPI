use std::convert::Infallible;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

use crate::client::XhttpClient;
use crate::config::{XhttpMode, XhttpTlsConfig};
use crate::h2_body::{ChannelBody, XhttpBody};

fn config() -> XhttpTlsConfig {
    XhttpTlsConfig::from_strings(
        "127.0.0.1",
        443,
        "peer.test",
        "550e8400-e29b-41d4-a716-446655440000",
        "/xhttp",
        "",
        "chrome_stable",
    )
    .expect("TLS config")
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns and aborts both local HTTP/2 drivers after the exchange.
async fn exchange_before_upload_response_headers(peer_stream_limit: u32, carrier_count: usize) {
    let mut config = config();
    config.xmux.max_connections = carrier_count;
    let mode = XhttpMode::Tls(config.clone());
    let client = XhttpClient::new_tls(config);
    let request = mode.tcp_request("127.0.0.1:443").expect("VLESS request");
    let request_len = request.len();
    let sessions = Arc::new(Mutex::new(std::collections::HashMap::new()));
    let mut servers = Vec::new();
    for _ in 0..carrier_count {
        let (client_io, server_io) = tokio::io::duplex(64 * 1024);
        let downloads = Arc::clone(&sessions);
        servers.push(tokio::spawn(async move {
            let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                let downloads = Arc::clone(&downloads);
                let path = request.uri().path().to_owned();
                async move {
                    let response: XhttpBody = if request.method() == hyper::Method::GET {
                        let (tx, rx) = mpsc::channel(2);
                        downloads.lock().expect("sessions").insert(path, tx);
                        ChannelBody::new(rx).boxed()
                    } else {
                        let download_tx = downloads.lock().expect("sessions").remove(&path).expect("matching GET");
                        let mut body = request.into_body();
                        let mut uploaded = Vec::new();
                        while uploaded.len() < request_len + 1 {
                            let frame = body.frame().await.expect("upload data").expect("valid upload");
                            if let Ok(data) = frame.into_data() {
                                uploaded.extend_from_slice(&data);
                            }
                        }

                        let response = vec![0, 0, uploaded[request_len]];
                        download_tx.send(Ok(Bytes::from(response))).await.expect("download payload");
                        Full::new(Bytes::new()).map_err(|never| match never {}).boxed()
                    };
                    Ok::<_, Infallible>(hyper::Response::new(response))
                }
            });
            let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .max_concurrent_streams(peer_stream_limit)
                .serve_connection(TokioIo::new(server_io), service)
                .await;
        }));
        let physical = client.inner.carrier_capacity.clone().acquire_owned().await.expect("physical capacity");
        let pooled = crate::connect::handshake_pooled(
            crate::owned_io::OwnedIo::new(client_io, physical),
            client.inner.max_concurrent_streams,
            Arc::clone(&client.inner.pool_changed),
        )
        .await
        .expect("H2 carrier");
        client.inner.state.lock().await.connections.push(pooled);
    }
    let exchange = |byte: u8| {
        let client = client.clone();
        async move {
            let mut stream = client.connect("127.0.0.1:443").await?;
            stream.write_u8(byte).await?;
            assert_eq!(stream.read_u8().await?, byte);
            std::io::Result::Ok(())
        }
    };
    let (first, next) = tokio::time::timeout(Duration::from_secs(2), async {
        if peer_stream_limit >= 2 && carrier_count > 1 {
            // Keep both halves of the first H2 session live throughout the second exchange.
            let mut first = client.connect("127.0.0.1:443").await.expect("first stream");
            let next = exchange(2).await;
            first.write_u8(1).await.expect("first upload");
            assert_eq!(first.read_u8().await.expect("first download"), 1);
            (Ok(()), next)
        } else {
            tokio::join!(exchange(1), exchange(2))
        }
    })
    .await
    .expect("stream admission and payload deadline");
    for result in [first, next] {
        if peer_stream_limit == 1 && carrier_count == 1 {
            assert_eq!(
                result.expect_err("one physical connection cannot serve both streams").kind(),
                std::io::ErrorKind::Unsupported
            );
        } else {
            result.expect("payload exchange before POST headers");
        }
    }
    drop(client);
    for task in servers {
        task.abort();
        if let Err(error) = task.await {
            assert!(error.is_cancelled());
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: inherits the test-owned HTTP/2 drivers in the exchange helper.
#[tokio::test]
async fn stream_up_does_not_wait_for_upload_response_headers() {
    exchange_before_upload_response_headers(2, 1).await;
}

/// # Cancel safety:
/// NOT cancel-safe: inherits the test-owned HTTP/2 drivers in the exchange helper.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stream_up_does_not_wait_for_upload_response_headers_multithread() {
    exchange_before_upload_response_headers(2, 1).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper owns the local HTTP/2 server task.
#[tokio::test]
async fn stream_up_splits_single_stream_peers_within_the_carrier_limit() {
    exchange_before_upload_response_headers(1, 1).await;
    exchange_before_upload_response_headers(1, 2).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper owns both local HTTP/2 servers until the exchange ends.
#[tokio::test]
async fn live_session_leaves_incomplete_peer_capacity_for_another_carrier() {
    for limit in [2, 3] {
        exchange_before_upload_response_headers(limit, 2).await;
    }
}
