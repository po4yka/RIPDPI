//! xHTTP `packet-up` upload leg: bounded POSTs to `{path}/{session}/{seq}`,
//! as in xray-core `splithttp/dialer.go` `Dial`.

use std::future::{Future, poll_fn};
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::Poll;
use std::time::Duration;

use bytes::{Bytes, BytesMut};
use http_body_util::BodyExt;
use hyper::StatusCode;
use tokio::io::{AsyncRead, DuplexStream, ReadBuf};
use tokio::sync::oneshot;
use tokio::task::{AbortHandle, JoinError, JoinHandle, JoinSet};
use tokio::time::Instant;

use crate::h2_body::build_packet_request;
use crate::pool::PooledConnection;
use crate::relay::{referer_padding, stream_up_path};

/// xray-core `GetNormalizedScMaxEachPostBytes` default.
pub(crate) const MAX_EACH_POST_BYTES: usize = 1_000_000;
/// Upload pipe capacity. Writers block when it is full. The pipe fills lazily,
/// so only a session that uploads at line rate holds it. A 256 KB pipe cut
/// loopback upload to about 75% of Xray's: with 30 ms pacing, the POST size
/// sets throughput.
pub(crate) const UPLOAD_PIPE_BYTES: usize = MAX_EACH_POST_BYTES;
/// Like xray-core, the next POST does not wait for the previous response; the
/// server reorders by seq (`upload_queue.go`, default 30 buffered posts).
const MAX_IN_FLIGHT_POSTS: usize = 4;
const MAX_IN_FLIGHT_BYTES: usize = 2 * MAX_EACH_POST_BYTES;
/// xray-core `GetNormalizedScMinPostsIntervalMs` default.
const MIN_POSTS_INTERVAL: Duration = Duration::from_millis(30);
/// A peer that never answers one POST fails the session instead of stalling it.
const POST_TIMEOUT: Duration = Duration::from_secs(60);
const READ_SIZE: usize = 16 * 1024;

/// Run the upload leg. `flushed` fires after upload EOF once every POST was
/// answered with 200. A failed POST publishes the failure before the upload
/// pipe closes and closes the download, so the session fails instead of
/// losing bytes.
pub(crate) fn spawn_upload(
    mut upload: DuplexStream,
    carrier: Arc<PooledConnection>,
    session_path: String,
    host: String,
    stream_failed: Arc<AtomicBool>,
    abort_download: AbortHandle,
    flushed: oneshot::Sender<()>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        match upload_packets(&mut upload, &carrier, &session_path, &host).await {
            Ok(()) => {
                let _ = flushed.send(());
            }
            Err(error) => {
                tracing::debug!(error = %error, "xHTTP packet-up upload failed");
                // Publish failure before the pipe and the flush sender drop.
                stream_failed.store(true, Ordering::Release);
                abort_download.abort();
            }
        }
        drop(upload);
    })
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation drops a partly read chunk and aborts the POSTs
/// in flight. Only the session's task guard cancels it, with the session.
async fn upload_packets(
    upload: &mut DuplexStream,
    carrier: &PooledConnection,
    session_path: &str,
    host: &str,
) -> io::Result<()> {
    let mut scratch = vec![0; READ_SIZE];
    let mut chunk = BytesMut::new();
    // Dropping the set aborts POSTs in flight.
    let mut posts = JoinSet::new();
    let mut in_flight_bytes = 0;
    let mut last_post: Option<Instant> = None;
    let mut seq: u64 = 0;
    loop {
        // Sleep before reading, so bytes written during the pause join this POST.
        if let Some(last) = last_post {
            tokio::time::sleep_until(last + MIN_POSTS_INTERVAL).await;
        }
        let eof = tokio::select! {
            read = fill_chunk(upload, &mut chunk, &mut scratch) => read?,
            Some(done) = posts.join_next(), if !posts.is_empty() => {
                in_flight_bytes -= post_result(done)?;
                continue;
            }
        };
        if !chunk.is_empty() {
            while !posts.is_empty()
                && (posts.len() >= MAX_IN_FLIGHT_POSTS || in_flight_bytes + chunk.len() > MAX_IN_FLIGHT_BYTES)
            {
                if let Some(done) = posts.join_next().await {
                    in_flight_bytes -= post_result(done)?;
                }
            }
            last_post = Some(Instant::now());
            let body = chunk.split().freeze();
            in_flight_bytes += body.len();
            let response = submit_post(carrier, &stream_up_path(session_path, &seq.to_string()), host, body).await?;
            posts.spawn(response);
            seq += 1;
        }
        if eof {
            // Like xray-core, upload EOF sends no marker; the GET stays open.
            while let Some(done) = posts.join_next().await {
                post_result(done)?;
            }
            return Ok(());
        }
    }
}

fn post_result(done: Result<io::Result<usize>, JoinError>) -> io::Result<usize> {
    done.map_err(|error| io::Error::other(format!("xHTTP packet-up POST task failed: {error}")))?
}

/// Wait for upload bytes, then take what is already buffered, up to
/// [`MAX_EACH_POST_BYTES`]. Returns true at upload EOF.
///
/// # Cancel safety:
/// cancel-safe: bytes already read stay in `chunk`.
async fn fill_chunk(upload: &mut DuplexStream, chunk: &mut BytesMut, scratch: &mut [u8]) -> io::Result<bool> {
    poll_fn(|cx| {
        loop {
            let room = (MAX_EACH_POST_BYTES - chunk.len()).min(scratch.len());
            if room == 0 {
                return Poll::Ready(Ok(false));
            }
            let mut buf = ReadBuf::new(&mut scratch[..room]);
            match Pin::new(&mut *upload).poll_read(cx, &mut buf) {
                Poll::Ready(Ok(())) if buf.filled().is_empty() => return Poll::Ready(Ok(true)),
                Poll::Ready(Ok(())) => chunk.extend_from_slice(buf.filled()),
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending if chunk.is_empty() => return Poll::Pending,
                Poll::Pending => return Poll::Ready(Ok(false)),
            }
        }
    })
    .await
}

/// Hand one POST to the H2 connection now, so seq order is the stream order,
/// and return its completion: only status 200 succeeds, as in xray-core
/// `DefaultDialerClient.PostPacket`, within [`POST_TIMEOUT`].
///
/// # Cancel safety:
/// cancel-safe: cancellation before submission sends nothing; after it, the
/// returned future owns the request.
async fn submit_post(
    carrier: &PooledConnection,
    path: &str,
    host: &str,
    body: Bytes,
) -> io::Result<impl Future<Output = io::Result<usize>> + Send + 'static> {
    let len = body.len();
    let request = build_packet_request(path, host, &referer_padding(host, path), body)?;
    let response = carrier.sender.lock().await.send_request(request);
    Ok(async move {
        let finish = async {
            let response = response.await.map_err(|error| {
                io::Error::new(io::ErrorKind::ConnectionReset, format!("xHTTP packet-up POST failed: {error}"))
            })?;
            if response.status() != StatusCode::OK {
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    format!("xHTTP packet-up POST rejected: {}", response.status()),
                ));
            }
            let mut body = response.into_body();
            while let Some(frame) = body.frame().await {
                frame.map_err(|error| {
                    io::Error::new(
                        io::ErrorKind::ConnectionReset,
                        format!("xHTTP packet-up POST response failed: {error}"),
                    )
                })?;
            }
            Ok(len)
        };
        tokio::time::timeout(POST_TIMEOUT, finish)
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "xHTTP packet-up POST timed out"))?
    })
}
