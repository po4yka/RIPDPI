use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{Notify, Semaphore, mpsc};
use tokio::task::JoinHandle;

use super::XhttpStream;
use crate::config::{XhttpMode, XhttpProtocolMode, XhttpTlsConfig};
use crate::h2_body::ChannelBody;
use crate::pool::PooledConnection;

type ResponseSender = mpsc::Sender<std::io::Result<Bytes>>;
const HALF_CLOSE_REPLY: &[u8] = b"download after upload EOF";

struct Peer {
    mode: XhttpMode,
    pooled: Arc<PooledConnection>,
    permits: Arc<Semaphore>,
    responses: mpsc::UnboundedReceiver<ResponseSender>,
    driver: JoinHandle<()>,
    server: JoinHandle<()>,
    uploads: Arc<Mutex<Vec<JoinHandle<()>>>>,
}

impl Peer {
    /// # Cancel safety:
    /// NOT cancel-safe: the test owns the spawned drivers and closes them with finish.
    async fn start(protocol_mode: XhttpProtocolMode) -> Self {
        let mode = XhttpMode::Tls(
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
            .with_protocol_mode(protocol_mode),
        );
        let request_len = mode.tcp_request("127.0.0.1:443").expect("VLESS request").len();
        let (client_io, server_io) = tokio::io::duplex(64 * 1024);
        let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
            .await
            .expect("client handshake");
        let driver = tokio::spawn(async move { connection.await.expect("client driver") });
        let (responses_tx, responses) = mpsc::unbounded_channel();
        let downloads = Arc::new(Mutex::new(HashMap::<String, ResponseSender>::new()));
        let uploads = Arc::new(Mutex::new(Vec::new()));
        let server_uploads = Arc::clone(&uploads);
        let server = tokio::spawn(async move {
            let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                let responses = responses_tx.clone();
                let downloads = Arc::clone(&downloads);
                let uploads = Arc::clone(&server_uploads);
                async move {
                    let (response_tx, response_rx) = mpsc::channel(8);
                    responses.send(response_tx.clone()).expect("observed response");
                    let path = request.uri().path().to_owned();
                    let download =
                        request.method() == hyper::Method::GET || protocol_mode == XhttpProtocolMode::StreamOne;
                    if download {
                        response_tx.send(Ok(Bytes::from_static(&[0, 0]))).await.expect("VLESS response header");
                        downloads.lock().expect("download map").insert(path.clone(), response_tx.clone());
                    }
                    if request.method() == hyper::Method::POST {
                        let download_tx =
                            downloads.lock().expect("download map").get(&path).expect("paired download").clone();
                        let mut body = request.into_body();
                        let upload = tokio::spawn(async move {
                            let mut prefix = request_len;
                            while let Some(Ok(frame)) = body.frame().await {
                                if let Ok(data) = frame.into_data() {
                                    let skip = prefix.min(data.len());
                                    prefix -= skip;
                                    if data.len() > skip && download_tx.send(Ok(data.slice(skip..))).await.is_err() {
                                        return;
                                    }
                                }
                            }
                            let _ = download_tx.send(Ok(Bytes::from_static(HALF_CLOSE_REPLY))).await;
                        });
                        uploads.lock().expect("upload tasks").push(upload);
                    }
                    Ok::<_, Infallible>(hyper::Response::new(ChannelBody::new(response_rx)))
                }
            });
            let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(server_io), service)
                .await;
        });
        Self {
            mode,
            pooled: Arc::new(PooledConnection::new(sender, 2, Arc::new(Notify::new()))),
            permits: Arc::new(Semaphore::new(2)),
            responses,
            driver,
            server,
            uploads,
        }
    }

    /// # Cancel safety:
    /// cancel-safe: opening owns its permit and request futures until return.
    async fn open(&self) -> XhttpStream {
        let permit = Arc::clone(&self.permits).acquire_owned().await.expect("stream capacity");
        let request = self.mode.tcp_request("127.0.0.1:443").expect("VLESS request");
        assert!(self.pooled.opening_started());
        tokio::time::timeout(Duration::from_secs(1), self.pooled.open_stream_from_mode(&self.mode, &request, permit))
            .await
            .expect("open deadline")
            .expect("open stream")
    }

    /// # Cancel safety:
    /// NOT cancel-safe: cancellation between receives drops collected peer-body
    /// notifications; the caller must discard the peer.
    async fn response_senders(&mut self) -> Vec<ResponseSender> {
        let count = if matches!(&self.mode, XhttpMode::Tls(config) if config.protocol_mode == XhttpProtocolMode::StreamUp)
        {
            2
        } else {
            1
        };
        let mut senders = Vec::new();
        for _ in 0..count {
            senders.push(
                tokio::time::timeout(Duration::from_secs(1), self.responses.recv())
                    .await
                    .expect("response deadline")
                    .expect("response"),
            );
        }
        senders
    }

    /// # Cancel safety:
    /// NOT cancel-safe: after abort, the helper joins each owned driver and upload task.
    async fn finish(self) {
        for task in [self.driver, self.server] {
            task.abort();
            match task.await {
                Ok(()) => {}
                Err(error) => assert!(error.is_cancelled()),
            }
        }
        let uploads = std::mem::take(&mut *self.uploads.lock().expect("upload tasks"));
        for task in uploads {
            task.abort();
            match task.await {
                Ok(()) => {}
                Err(error) => assert!(error.is_cancelled()),
            }
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns local peer tasks and calls finish after its exchange.
async fn dropping_stream_closes_its_responses_and_preserves_siblings() {
    for mode in [XhttpProtocolMode::StreamUp, XhttpProtocolMode::StreamOne] {
        let mut peer = Peer::start(mode).await;
        let dropped = peer.open().await;
        let responses = peer.response_senders().await;
        let mut sibling = peer.open().await;
        let sibling_responses = peer.response_senders().await;
        sibling.write_all(b"before").await.expect("sibling upload");
        let mut before = [0; 6];
        sibling.read_exact(&mut before).await.expect("sibling reply");
        assert_eq!(&before, b"before");
        drop(dropped);
        for response in responses {
            tokio::time::timeout(Duration::from_secs(1), response.closed())
                .await
                .expect("full drop must close idle HTTP response");
        }
        sibling.write_all(b"after").await.expect("sibling upload after drop");
        let mut after = [0; 5];
        sibling.read_exact(&mut after).await.expect("sibling reply after drop");
        assert_eq!(&after, b"after");
        assert!(sibling_responses.iter().all(|response| !response.is_closed()));
        drop(sibling);
        peer.finish().await;
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the helper owns and finishes each local peer.
#[tokio::test]
async fn dropped_stream_resets_idle_http_legs_and_preserves_siblings() {
    dropping_stream_closes_its_responses_and_preserves_siblings().await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper owns and finishes each local peer.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn dropped_stream_resets_idle_http_legs_and_preserves_siblings_multithread() {
    dropping_stream_closes_its_responses_and_preserves_siblings().await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns each peer and finishes it after observing the reply.
#[tokio::test]
async fn upload_half_close_preserves_download_in_both_modes() {
    for mode in [XhttpProtocolMode::StreamUp, XhttpProtocolMode::StreamOne] {
        let mut peer = Peer::start(mode).await;
        let mut stream = peer.open().await;
        let responses = peer.response_senders().await;
        stream.shutdown().await.expect("write-half shutdown");
        let mut reply = vec![0; HALF_CLOSE_REPLY.len()];
        tokio::time::timeout(Duration::from_secs(1), stream.read_exact(&mut reply))
            .await
            .expect("post-EOF reply deadline")
            .expect("post-EOF download");
        assert_eq!(reply, HALF_CLOSE_REPLY);
        assert!(responses.iter().all(|response| !response.is_closed()));
        drop(stream);
        peer.finish().await;
    }
}

struct RequestEnded(Option<tokio::sync::oneshot::Sender<()>>);

impl Drop for RequestEnded {
    fn drop(&mut self) {
        if let Some(sender) = self.0.take() {
            let _ = sender.send(());
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns both H2 drivers and aborts and joins them after request teardown.
#[tokio::test]
async fn dropping_stream_cancels_pending_upload_response_headers() {
    let mode = XhttpMode::Tls(
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
    );
    let (client_io, server_io) = tokio::io::duplex(64 * 1024);
    let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
        .await
        .expect("client handshake");
    let driver = tokio::spawn(async move { connection.await.expect("client driver") });
    let (download_tx, download_rx) = mpsc::channel(1);
    let download = Arc::new(Mutex::new(Some(download_rx)));
    let (ended_tx, ended_rx) = tokio::sync::oneshot::channel();
    let ended = Arc::new(Mutex::new(Some(ended_tx)));
    let seen = Arc::new(Notify::new());
    let server_seen = Arc::clone(&seen);
    let server = tokio::spawn(async move {
        let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
            let download = Arc::clone(&download);
            let ended = Arc::clone(&ended);
            let seen = Arc::clone(&server_seen);
            async move {
                if request.method() == hyper::Method::GET {
                    let body = ChannelBody::new(download.lock().expect("download lock").take().expect("GET body"));
                    return Ok::<_, Infallible>(hyper::Response::new(body));
                }
                let guard = RequestEnded(ended.lock().expect("ended sender").take());
                seen.notify_one();
                std::future::pending::<()>().await;
                drop((request, guard));
                Ok(hyper::Response::new(ChannelBody::new(mpsc::channel(1).1)))
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(server_io), service)
            .await;
    });
    let pooled = Arc::new(PooledConnection::new(sender, 1, Arc::new(Notify::new())));
    let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("stream capacity");
    let request = mode.tcp_request("127.0.0.1:443").expect("VLESS request");
    assert!(pooled.opening_started());
    let stream = tokio::time::timeout(Duration::from_secs(1), pooled.open_stream_from_mode(&mode, &request, permit))
        .await
        .expect("open without POST response headers")
        .expect("open stream");
    tokio::time::timeout(Duration::from_secs(1), seen.notified()).await.expect("POST reached peer");
    drop(stream);
    tokio::time::timeout(Duration::from_secs(1), ended_rx)
        .await
        .expect("drop cancels POST waiting for headers")
        .expect("POST handler dropped");
    tokio::time::timeout(Duration::from_secs(1), download_tx.closed())
        .await
        .expect("drop also closes idle GET response");
    drop(pooled);
    for task in [driver, server] {
        task.abort();
        match task.await {
            Ok(()) => {}
            Err(error) => assert!(error.is_cancelled()),
        }
    }
}
