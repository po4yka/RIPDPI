//! xHTTP `packet-up` upload leg: sequential bounded POSTs to
//! `{path}/{session}/{seq}`, as in xray-core `splithttp/dialer.go` `Dial`.

use std::future::poll_fn;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::Poll;
use std::time::Duration;

use bytes::BytesMut;
use http_body_util::BodyExt;
use hyper::StatusCode;
use tokio::io::{AsyncRead, DuplexStream, ReadBuf};
use tokio::task::{AbortHandle, JoinHandle};
use tokio::time::Instant;

use crate::h2_body::build_packet_request;
use crate::pool::PooledConnection;
use crate::relay::{referer_padding, stream_up_path};

/// xray-core `GetNormalizedScMaxEachPostBytes` default. The upload pipe holds
/// at most this many bytes, so callers block instead of queueing more.
pub(crate) const MAX_EACH_POST_BYTES: usize = 1_000_000;
/// xray-core `GetNormalizedScMinPostsIntervalMs` default.
const MIN_POSTS_INTERVAL: Duration = Duration::from_millis(30);
const READ_SIZE: usize = 16 * 1024;

/// Run the upload leg. A failed POST publishes the failure and closes the
/// download, so the session fails instead of losing bytes.
pub(crate) fn spawn_upload(
    upload: DuplexStream,
    carrier: Arc<PooledConnection>,
    session_path: String,
    host: String,
    stream_failed: Arc<AtomicBool>,
    abort_download: AbortHandle,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        if let Err(error) = upload_packets(upload, &carrier, &session_path, &host).await {
            tracing::debug!(error = %error, "xHTTP packet-up upload failed");
            // Publish failure before pipe closure wakes a pending read or write.
            stream_failed.store(true, Ordering::Release);
            abort_download.abort();
        }
    })
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation drops a partly read chunk and may reset an
/// in-flight POST. Only the session's task guard cancels it, with the session.
async fn upload_packets(
    mut upload: DuplexStream,
    carrier: &PooledConnection,
    session_path: &str,
    host: &str,
) -> io::Result<()> {
    let mut scratch = vec![0; READ_SIZE];
    let mut chunk = BytesMut::new();
    let mut last_post: Option<Instant> = None;
    let mut seq: u64 = 0;
    loop {
        // Sleep before reading, so bytes written during the pause join this POST.
        if let Some(last) = last_post {
            tokio::time::sleep_until(last + MIN_POSTS_INTERVAL).await;
        }
        let eof = fill_chunk(&mut upload, &mut chunk, &mut scratch).await?;
        if !chunk.is_empty() {
            last_post = Some(Instant::now());
            let path = stream_up_path(session_path, &seq.to_string());
            post_packet(carrier, &path, host, chunk.split().freeze()).await?;
            seq += 1;
        }
        if eof {
            // Like xray-core, upload EOF sends no marker; the GET stays open.
            return Ok(());
        }
    }
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

/// xray-core `DefaultDialerClient.PostPacket`: only status 200 succeeds.
///
/// # Cancel safety:
/// NOT cancel-safe: cancellation may reset a partly sent POST.
async fn post_packet(carrier: &PooledConnection, path: &str, host: &str, body: bytes::Bytes) -> io::Result<()> {
    let request = build_packet_request(path, host, &referer_padding(host, path), body)?;
    let response = carrier.send_request(request).await.map_err(|error| {
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
            io::Error::new(io::ErrorKind::ConnectionReset, format!("xHTTP packet-up POST response failed: {error}"))
        })?;
    }
    Ok(())
}
