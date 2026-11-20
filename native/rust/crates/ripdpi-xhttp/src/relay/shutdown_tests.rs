use std::convert::Infallible;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, ready};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::sync::{Notify, Semaphore, mpsc, oneshot};

use crate::config::{XhttpMode, XhttpProtocolMode, XhttpTlsConfig};
use crate::h2_body::ChannelBody;
use crate::pool::PooledConnection;

/// Server side of the in-memory link that reads at most 1 KiB per millisecond,
/// so the client's H2 send queue holds frames when the stream is dropped.
struct SlowReader {
    io: DuplexStream,
    pause: Option<Pin<Box<tokio::time::Sleep>>>,
}

impl AsyncRead for SlowReader {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        if let Some(pause) = self.pause.as_mut() {
            ready!(pause.as_mut().poll(cx));
            self.pause = None;
        }
        let limit = buf.remaining().min(1024);
        let mut slice = ReadBuf::new(buf.initialize_unfilled_to(limit));
        ready!(Pin::new(&mut self.io).poll_read(cx, &mut slice))?;
        let read = slice.filled().len();
        buf.advance(read);
        self.pause = Some(Box::pin(tokio::time::sleep(Duration::from_millis(1))));
        Poll::Ready(Ok(()))
    }
}

impl AsyncWrite for SlowReader {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        Pin::new(&mut self.io).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.io).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.io).poll_shutdown(cx)
    }
}

/// Write `len` bytes, shut down, drop the stream at once, and return how many
/// upload bytes the peer read. Like Xray, the peer keeps its upload response
/// open; it ends it only after reading the whole request body.
///
/// # Cancel safety:
/// NOT cancel-safe: the test owns the driver, server and reader tasks.
async fn bytes_received_after_shutdown_and_drop(protocol_mode: XhttpProtocolMode, len: usize) -> usize {
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
    let request = mode.tcp_request("127.0.0.1:443").expect("VLESS request");
    let (client_io, server_io) = tokio::io::duplex(16 * 1024);
    let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
        .await
        .expect("client H2 handshake");
    let driver = tokio::spawn(async move {
        let _ = connection.await;
    });
    let (total_tx, total_rx) = oneshot::channel::<usize>();
    let total_tx = Arc::new(Mutex::new(Some(total_tx)));
    let keep = Arc::new(Mutex::new(Vec::new()));
    let server = tokio::spawn(async move {
        let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
            let (total_tx, keep) = (Arc::clone(&total_tx), Arc::clone(&keep));
            async move {
                let (response_tx, response_rx) = mpsc::channel::<std::io::Result<Bytes>>(4);
                let downloads = request.method() == hyper::Method::GET || protocol_mode == XhttpProtocolMode::StreamOne;
                if downloads {
                    response_tx.send(Ok(Bytes::from_static(&[0, 0]))).await.expect("VLESS response header");
                }
                if request.method() == hyper::Method::POST {
                    let mut body = request.into_body();
                    tokio::spawn(async move {
                        let mut total = 0;
                        while let Some(Ok(frame)) = body.frame().await {
                            total += frame.into_data().map_or(0, |data| data.len());
                        }
                        if let Some(sender) = total_tx.lock().expect("total").take() {
                            let _ = sender.send(total);
                        }
                        drop(response_tx);
                    });
                } else {
                    keep.lock().expect("keep").push(response_tx);
                }
                Ok::<_, Infallible>(hyper::Response::new(ChannelBody::new(response_rx).boxed()))
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(SlowReader { io: server_io, pause: None }), service)
            .await;
    });
    let pooled = Arc::new(PooledConnection::new(sender, 2, Arc::new(Notify::new())));
    let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("stream capacity");
    assert!(pooled.opening_started());
    let mut stream =
        tokio::time::timeout(Duration::from_secs(5), pooled.open_stream_from_mode(&mode, &request, permit, None))
            .await
            .expect("open deadline")
            .expect("open stream");
    stream.write_all(&vec![7; len]).await.expect("upload");
    tokio::time::timeout(Duration::from_secs(10), stream.shutdown()).await.expect("flush deadline").expect("flush");
    drop(stream);
    let total = tokio::time::timeout(Duration::from_secs(20), total_rx)
        .await
        .expect("the peer must see the request end")
        .expect("total");
    driver.abort();
    server.abort();
    total - request.len()
}

/// # Cancel safety:
/// NOT cancel-safe: the helper owns its peer.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stream_modes_deliver_every_byte_when_dropped_right_after_shutdown() {
    for protocol_mode in [XhttpProtocolMode::StreamUp, XhttpProtocolMode::StreamOne] {
        let len = 200_000;
        assert_eq!(len, bytes_received_after_shutdown_and_drop(protocol_mode, len).await, "{protocol_mode:?}");
    }
}
