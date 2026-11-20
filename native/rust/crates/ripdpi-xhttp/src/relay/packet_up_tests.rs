use std::collections::BTreeMap;
use std::convert::Infallible;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use bytes::{Bytes, BytesMut};
use http_body_util::{BodyExt, Full};
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{Notify, Semaphore, mpsc};
use tokio::task::JoinHandle;

use super::XhttpStream;
use crate::config::{XhttpMode, XhttpProtocolMode, XhttpTlsConfig};
use crate::h2_body::ChannelBody;
use crate::pool::PooledConnection;

/// xray-core defaults, restated here: `GetNormalizedScMaxEachPostBytes` and
/// `GetNormalizedScMinPostsIntervalMs`.
const XRAY_MAX_EACH_POST_BYTES: usize = 1_000_000;
const XRAY_MIN_POSTS_INTERVAL: Duration = Duration::from_millis(30);

type Download = mpsc::Sender<std::io::Result<Bytes>>;

struct Post {
    seq: u64,
    path: String,
    arrived: Instant,
    content_length: Option<String>,
    has_content_type: bool,
    body: Bytes,
}

/// Like xray-core's `uploadQueue`, the peer delivers POST bodies in seq order.
#[derive(Default)]
struct Reorder {
    next: u64,
    pending: BTreeMap<u64, Bytes>,
}

struct Peer {
    stream: XhttpStream,
    request_len: usize,
    get_paths: Arc<Mutex<Vec<String>>>,
    posts: Arc<Mutex<Vec<Post>>>,
    download: Download,
    tasks: Vec<JoinHandle<()>>,
}

impl Peer {
    /// Open a packet-up stream against an in-process H2 peer. The peer echoes
    /// the upload bytes after the VLESS request on the GET body and answers a
    /// POST that contains `reject` with 403.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: the test owns the spawned driver and server tasks.
    async fn open(reject: Option<&'static [u8]>) -> Self {
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
            .with_protocol_mode(XhttpProtocolMode::PacketUp),
        );
        let request = mode.tcp_request("127.0.0.1:443").expect("VLESS request");
        let request_len = request.len();
        let (client_io, server_io) = tokio::io::duplex(64 * 1024);
        let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
            .await
            .expect("client H2 handshake");
        let driver = tokio::spawn(async move {
            let _ = connection.await;
        });
        let get_paths = Arc::new(Mutex::new(Vec::new()));
        let posts = Arc::new(Mutex::new(Vec::new()));
        let (download_tx, download_rx) = mpsc::channel::<std::io::Result<Bytes>>(8);
        let download = download_tx.clone();
        let download_rx = Arc::new(Mutex::new(Some(download_rx)));
        let state = Arc::new(tokio::sync::Mutex::new((request_len, Reorder::default())));
        let (server_gets, server_posts) = (Arc::clone(&get_paths), Arc::clone(&posts));
        let server = tokio::spawn(async move {
            let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                let (gets, posts, download_rx) =
                    (Arc::clone(&server_gets), Arc::clone(&server_posts), Arc::clone(&download_rx));
                let (download_tx, state) = (download_tx.clone(), Arc::clone(&state));
                async move {
                    let path = request.uri().path().to_owned();
                    if request.method() == hyper::Method::GET {
                        gets.lock().expect("GET paths").push(path);
                        download_tx.send(Ok(Bytes::from_static(&[0, 0]))).await.expect("VLESS response header");
                        let body = ChannelBody::new(download_rx.lock().expect("download").take().expect("one GET"));
                        return Ok::<_, Infallible>(hyper::Response::new(body.boxed()));
                    }
                    let arrived = Instant::now();
                    let headers = request.headers().clone();
                    let seq: u64 = path.rsplit('/').next().and_then(|seq| seq.parse().ok()).expect("decimal seq");
                    let body = request.into_body().collect().await.expect("POST body").to_bytes();
                    posts.lock().expect("posts").push(Post {
                        seq,
                        path: path.clone(),
                        arrived,
                        content_length: headers
                            .get(http::header::CONTENT_LENGTH)
                            .map(|value| value.to_str().expect("ASCII").to_owned()),
                        has_content_type: headers.contains_key(http::header::CONTENT_TYPE),
                        body: body.clone(),
                    });
                    let mut response =
                        hyper::Response::new(Full::new(Bytes::new()).map_err(|never| match never {}).boxed());
                    if reject.is_some_and(|marker| body.windows(marker.len()).any(|window| window == marker)) {
                        *response.status_mut() = hyper::StatusCode::FORBIDDEN;
                        return Ok(response);
                    }
                    let mut state = state.lock().await;
                    let (skip, reorder) = &mut *state;
                    reorder.pending.insert(seq, body);
                    while let Some(body) = reorder.pending.remove(&reorder.next) {
                        reorder.next += 1;
                        let drop_len = (*skip).min(body.len());
                        *skip -= drop_len;
                        if body.len() > drop_len {
                            let _ = download_tx.send(Ok(body.slice(drop_len..))).await;
                        }
                    }
                    Ok(response)
                }
            });
            let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(server_io), service)
                .await;
        });
        let pooled = Arc::new(PooledConnection::new(sender, 2, Arc::new(Notify::new())));
        let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("stream capacity");
        assert!(pooled.opening_started());
        let stream =
            tokio::time::timeout(Duration::from_secs(1), pooled.open_stream_from_mode(&mode, &request, permit, None))
                .await
                .expect("open deadline")
                .expect("open packet-up");
        Self { stream, request_len, get_paths, posts, download, tasks: vec![driver, server] }
    }

    /// POSTs in seq order.
    fn finish(self) -> (Vec<String>, Vec<Post>) {
        drop(self.stream);
        for task in self.tasks {
            task.abort();
        }
        let gets = std::mem::take(&mut *self.get_paths.lock().expect("GET paths"));
        let mut posts = std::mem::take(&mut *self.posts.lock().expect("posts"));
        posts.sort_by_key(|post| post.seq);
        (gets, posts)
    }
}

fn payload(len: usize) -> Vec<u8> {
    (0..len).map(|index| u8::try_from((index * 31 + 7) % 251).expect("byte")).collect()
}

/// The upload bytes after the VLESS request, in seq order, checking that the
/// seqs are 0, 1, 2, ... without gaps.
fn uploaded(posts: &[Post], request_len: usize) -> Vec<u8> {
    let mut uploaded = BytesMut::new();
    for (index, post) in posts.iter().enumerate() {
        assert_eq!(u64::try_from(index).expect("seq"), post.seq, "seq gap");
        uploaded.extend_from_slice(&post.body);
    }
    uploaded[request_len..].to_vec()
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the peer and its reader and writer tasks.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn packet_up_echoes_payload_through_ordered_bounded_posts() {
    let peer = Peer::open(None).await;
    let Peer { stream, request_len, get_paths, posts, download, tasks } = peer;
    let sent = payload(3 * XRAY_MAX_EACH_POST_BYTES + 500_000);
    let expected = sent.clone();
    let (mut read, mut write) = tokio::io::split(stream);
    let writer = tokio::spawn(async move {
        write.write_all(&sent).await.expect("upload payload");
        write.shutdown().await.expect("upload EOF");
        write
    });
    let mut received = vec![0; expected.len()];
    tokio::time::timeout(Duration::from_secs(20), read.read_exact(&mut received))
        .await
        .expect("echo deadline")
        .expect("echo");
    assert!(received == expected, "echoed bytes differ from the upload");
    let write = writer.await.expect("writer task");
    let peer = Peer { stream: read.unsplit(write), request_len, get_paths, posts, download, tasks };
    let (gets, posts) = peer.finish();

    assert_eq!(1, gets.len(), "one long-lived GET");
    let session = gets[0].strip_prefix("/xhttp/").expect("GET path is {path}/{session}");
    assert!(!session.is_empty() && !session.contains('/'), "GET path carries one session segment");
    assert!(posts.len() >= 4, "3.5 MB needs at least four POSTs of at most 1 MB, got {}", posts.len());
    for post in &posts {
        let seq = post.seq;
        assert_eq!(format!("/xhttp/{session}/{seq}"), post.path, "POST {seq} path");
        assert!(post.body.len() <= XRAY_MAX_EACH_POST_BYTES, "POST {seq} has {} bytes", post.body.len());
        assert!(!post.body.is_empty(), "POST {seq} is empty");
        assert_eq!(Some(post.body.len().to_string()), post.content_length, "POST {seq} Content-Length");
        assert!(!post.has_content_type, "packet-up POST {seq} carries a Content-Type");
    }
    assert!(uploaded(&posts, request_len) == expected, "POST bodies in seq order differ from the upload");
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the peer and its writer task.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn packet_up_posts_start_at_least_the_minimum_interval_apart() {
    let mut peer = Peer::open(None).await;
    // Small writes spaced so each one becomes its own POST.
    for part in 0..5u8 {
        peer.stream.write_all(&[part; 100]).await.expect("upload");
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    peer.stream.shutdown().await.expect("flush");
    let (_, mut posts) = peer.finish();
    posts.sort_by_key(|post| post.arrived);
    assert!(posts.len() >= 2, "need two POSTs to measure pacing");
    for pair in posts.windows(2) {
        let gap = pair[1].arrived.duration_since(pair[0].arrived);
        // Arrival jitter is small against the 30 ms interval.
        assert!(gap + Duration::from_millis(5) >= XRAY_MIN_POSTS_INTERVAL, "POSTs {gap:?} apart");
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the peer tasks.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn packet_up_shutdown_delivers_every_accepted_byte_before_drop() {
    let mut peer = Peer::open(None).await;
    let request_len = peer.request_len;
    let sent = payload(300_000);
    peer.stream.write_all(&sent).await.expect("upload");
    tokio::time::timeout(Duration::from_secs(10), peer.stream.shutdown())
        .await
        .expect("flush deadline")
        .expect("flush");
    // Drop right after shutdown: nothing may still be in flight.
    let (_, posts) = peer.finish();
    assert_eq!(sent, uploaded(&posts, request_len), "the peer must hold exactly the accepted bytes");
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the peer tasks.
#[tokio::test]
async fn packet_up_rejected_post_fails_the_session() {
    let mut peer = Peer::open(Some(b"second".as_slice())).await;
    peer.stream.write_all(b"first").await.expect("first upload");
    let mut first = [0; 5];
    tokio::time::timeout(Duration::from_secs(1), peer.stream.read_exact(&mut first))
        .await
        .expect("first echo deadline")
        .expect("first echo");
    assert_eq!(b"first", &first);

    peer.stream.write_all(b"second").await.expect("second write is buffered");
    let mut rest = Vec::new();
    let error = tokio::time::timeout(Duration::from_secs(1), peer.stream.read_to_end(&mut rest))
        .await
        .expect("rejection wakes the reader")
        .expect_err("a rejected POST must not become EOF");
    assert_eq!(std::io::ErrorKind::ConnectionReset, error.kind());
    let error = peer.stream.write_all(b"third").await.expect_err("writes must observe the failed upload");
    assert_eq!(std::io::ErrorKind::ConnectionReset, error.kind());
    let error = peer.stream.shutdown().await.expect_err("shutdown must not report a clean flush");
    assert_eq!(std::io::ErrorKind::ConnectionReset, error.kind());
    tokio::time::sleep(Duration::from_millis(100)).await;

    let (_, posts) = peer.finish();
    let last = posts.last().expect("rejected POST");
    assert!(last.body.ends_with(b"second"), "no POST follows the rejected one");
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the peer tasks.
#[tokio::test]
async fn packet_up_get_failure_fails_reads_and_writes_while_uploads_work() {
    let mut peer = Peer::open(None).await;
    peer.stream.write_all(b"before").await.expect("upload before the GET fails");
    let mut before = [0; 6];
    peer.stream.read_exact(&mut before).await.expect("echo before the GET fails");
    peer.download.send(Err(std::io::Error::other("GET reset"))).await.expect("reset the GET body");
    let mut rest = Vec::new();
    let error = tokio::time::timeout(Duration::from_secs(1), peer.stream.read_to_end(&mut rest))
        .await
        .expect("GET failure wakes the reader")
        .expect_err("a failed GET must not become EOF");
    assert_eq!(std::io::ErrorKind::ConnectionReset, error.kind());
    let error = peer.stream.write_all(b"after").await.expect_err("writes must observe the failed GET");
    assert_eq!(std::io::ErrorKind::ConnectionReset, error.kind());
    peer.finish();
}
