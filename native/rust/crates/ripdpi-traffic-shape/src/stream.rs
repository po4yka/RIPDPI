use std::{
    collections::VecDeque,
    io,
    pin::Pin,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    task::{Context, Poll},
};

use futures::task::AtomicWaker;
use tokio::{
    io::{
        AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf, ReadHalf, WriteHalf, duplex, split,
    },
    sync::mpsc,
    task::{JoinHandle, JoinSet},
    time::{Instant, MissedTickBehavior, interval_at},
};

use crate::{
    profile::{OpusVoip, TrafficShapeProfile, WebRtcVideo},
    stats::TrafficShapeStats,
};

const FRAME_HEADER_BYTES: usize = 4;
const LOCAL_BUFFER_BYTES: usize = 64 * 1024;
const MAX_BUFFERED_REAL_BYTES: usize = 64 * 1024;
const FAILURE_CHANNEL_CAPACITY: usize = 1;

/// Wraps an asynchronous byte stream in a cooperative shaping codec.
///
/// Both peers must use the same profile. The wrapper is intended to sit above
/// an already encrypted transport; it does not promise TLS, TCP, or QUIC wire
/// packet boundaries.
pub trait Shaper {
    /// Starts a shaped stream on the current Tokio runtime.
    ///
    /// # Panics
    ///
    /// Panics when called outside a Tokio runtime.
    fn wrap<S>(self, stream: S) -> ShapedStream
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static;
}

/// An `AsyncRead + AsyncWrite` application view over a shaped peer stream.
///
/// [`AsyncWrite::poll_flush`] waits until every real byte accepted before the
/// call has been written and flushed to the peer stream. Shutdown flushes and
/// half-closes the outgoing direction; incoming data remains readable. Dropping
/// without a successful flush aborts the worker and may discard queued bytes.
pub struct ShapedStream {
    application_stream: DuplexStream,
    worker: Option<JoinHandle<io::Result<()>>>,
    stats: TrafficShapeStats,
    flush_state: Arc<FlushState>,
    failure_receiver: mpsc::Receiver<io::Error>,
}

#[derive(Debug, Default)]
struct FlushState {
    accepted_real_bytes: AtomicU64,
    transmitted_real_bytes: AtomicU64,
    outbound_closed: std::sync::atomic::AtomicBool,
    waker: AtomicWaker,
}

impl ShapedStream {
    /// Returns the lock-free aggregate counters for this endpoint.
    #[must_use]
    pub fn stats(&self) -> TrafficShapeStats {
        self.stats.clone()
    }

    /// Flushes outgoing bytes, closes the application write half, and waits for
    /// the peer read half to finish.
    ///
    /// # Errors
    ///
    /// Returns the first shaping, peer I/O, or worker join error.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: after shutdown begins, cancelling
    /// it can leave the peer read half incomplete. Dropping the consumed stream
    /// still aborts the owned worker and prevents a detached task.
    pub async fn close(mut self) -> io::Result<()> {
        AsyncWriteExt::shutdown(&mut self).await?;
        let Some(worker) = self.worker.as_mut() else {
            return Ok(());
        };
        let result = match worker.await {
            Ok(result) => result,
            Err(error) => Err(io::Error::other(error)),
        };
        self.worker = None;
        result
    }

    fn poll_failure(&mut self, cx: &mut Context<'_>) -> Poll<Option<io::Error>> {
        self.failure_receiver.poll_recv(cx)
    }
}

impl Shaper for OpusVoip {
    fn wrap<S>(self, stream: S) -> ShapedStream
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        wrap_stream(stream, TrafficShapeProfile::OpusVoip)
    }
}

impl Shaper for WebRtcVideo {
    fn wrap<S>(self, stream: S) -> ShapedStream
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        wrap_stream(stream, TrafficShapeProfile::WebRtcVideo)
    }
}

impl Shaper for TrafficShapeProfile {
    fn wrap<S>(self, stream: S) -> ShapedStream
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        wrap_stream(stream, self)
    }
}

impl AsyncRead for ShapedStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buffer: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if buffer.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        let filled_before = buffer.filled().len();
        match Pin::new(&mut self.application_stream).poll_read(cx, buffer) {
            Poll::Ready(Ok(())) if buffer.filled().len() == filled_before => match self.poll_failure(cx) {
                Poll::Ready(Some(error)) => Poll::Ready(Err(error)),
                Poll::Ready(None) | Poll::Pending => Poll::Ready(Ok(())),
            },
            result => result,
        }
    }
}

impl AsyncWrite for ShapedStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buffer: &[u8]) -> Poll<Result<usize, io::Error>> {
        match Pin::new(&mut self.application_stream).poll_write(cx, buffer) {
            Poll::Ready(Ok(written)) => {
                self.flush_state.accepted_real_bytes.fetch_add(written as u64, Ordering::Relaxed);
                Poll::Ready(Ok(written))
            }
            Poll::Ready(Err(error)) => match self.poll_failure(cx) {
                Poll::Ready(Some(worker_error)) => Poll::Ready(Err(worker_error)),
                Poll::Ready(None) | Poll::Pending => Poll::Ready(Err(error)),
            },
            Poll::Pending => Poll::Pending,
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        match Pin::new(&mut self.application_stream).poll_flush(cx) {
            Poll::Ready(Ok(())) => {}
            result => return result,
        }

        let target = self.flush_state.accepted_real_bytes.load(Ordering::Relaxed);
        if self.flush_state.transmitted_real_bytes.load(Ordering::Acquire) >= target {
            return Poll::Ready(Ok(()));
        }
        self.flush_state.waker.register(cx.waker());
        if self.flush_state.transmitted_real_bytes.load(Ordering::Acquire) >= target {
            return Poll::Ready(Ok(()));
        }
        match self.poll_failure(cx) {
            Poll::Ready(Some(error)) => Poll::Ready(Err(error)),
            Poll::Ready(None) => Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "traffic-shaping worker stopped before flush completed",
            ))),
            Poll::Pending => Poll::Pending,
        }
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        match self.as_mut().poll_flush(cx) {
            Poll::Ready(Ok(())) => {}
            result => return result,
        }
        match Pin::new(&mut self.application_stream).poll_shutdown(cx) {
            Poll::Ready(Ok(())) => {}
            result => return result,
        }
        if self.flush_state.outbound_closed.load(Ordering::Acquire) {
            return Poll::Ready(Ok(()));
        }
        self.flush_state.waker.register(cx.waker());
        if self.flush_state.outbound_closed.load(Ordering::Acquire) {
            return Poll::Ready(Ok(()));
        }
        match self.poll_failure(cx) {
            Poll::Ready(Some(error)) => Poll::Ready(Err(error)),
            Poll::Ready(None) => Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "traffic-shaping worker stopped before shutdown completed",
            ))),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl Drop for ShapedStream {
    fn drop(&mut self) {
        if let Some(worker) = self.worker.take() {
            worker.abort();
        }
    }
}

fn wrap_stream<S>(stream: S, profile: TrafficShapeProfile) -> ShapedStream
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (application_stream, worker_stream) = duplex(LOCAL_BUFFER_BYTES);
    let stats = TrafficShapeStats::default();
    let worker_stats = stats.clone();
    let flush_state = Arc::new(FlushState::default());
    let worker_flush_state = Arc::clone(&flush_state);
    let (failure_sender, failure_receiver) = mpsc::channel(FAILURE_CHANNEL_CAPACITY);
    let worker = tokio::spawn(async move {
        run_session(worker_stream, stream, profile, worker_stats, worker_flush_state, failure_sender).await
    });

    ShapedStream { application_stream, worker: Some(worker), stats, flush_state, failure_receiver }
}

/// Runs both directional pumps until either direction reaches a terminal result.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation can discard queued
/// real bytes. Dropping it does abort both pump tasks through [`JoinSet`], so no
/// detached I/O task or lock survives the owning shaped session.
async fn run_session<S>(
    application_stream: DuplexStream,
    peer_stream: S,
    profile: TrafficShapeProfile,
    stats: TrafficShapeStats,
    flush_state: Arc<FlushState>,
    failure_sender: mpsc::Sender<io::Error>,
) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (application_reader, application_writer) = split(application_stream);
    let (peer_reader, peer_writer) = split(peer_stream);
    let mut pumps = JoinSet::new();
    let outbound_failure_sender = failure_sender.clone();
    let outbound_stats = stats.clone();
    pumps.spawn(async move {
        let mut application_reader = application_reader;
        let mut peer_writer = peer_writer;
        let result =
            pump_outbound(&mut application_reader, &mut peer_writer, profile, outbound_stats, flush_state).await;
        if let Err(error) = &result {
            let _ = outbound_failure_sender.try_send(copy_io_error(error));
        }
        result
    });
    pumps.spawn(async move {
        let mut peer_reader = peer_reader;
        let mut application_writer = application_writer;
        let result = pump_inbound(&mut peer_reader, &mut application_writer, profile, stats).await;
        if let Err(error) = &result {
            let _ = failure_sender.try_send(copy_io_error(error));
        }
        result
    });

    let first_result = match pumps.join_next().await {
        Some(Ok(result)) => result,
        Some(Err(error)) => Err(io::Error::other(error)),
        None => Err(io::Error::other("traffic-shaping session started without pumps")),
    };
    match first_result {
        Ok(()) => match pumps.join_next().await {
            Some(Ok(result)) => result,
            Some(Err(error)) => Err(io::Error::other(error)),
            None => Ok(()),
        },
        Err(error) => {
            pumps.abort_all();
            while pumps.join_next().await.is_some() {}
            Err(error)
        }
    }
}

/// Emits one framed record per profile tick.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation may discard buffered
/// real bytes or a partially written frame. Its owning [`JoinSet`] cancels it
/// only when the complete shaped session reaches a terminal state.
async fn pump_outbound<W>(
    application_reader: &mut ReadHalf<DuplexStream>,
    peer_writer: &mut WriteHalf<W>,
    profile: TrafficShapeProfile,
    stats: TrafficShapeStats,
    flush_state: Arc<FlushState>,
) -> io::Result<()>
where
    W: AsyncWrite + Unpin,
{
    let mut pending = VecDeque::with_capacity(MAX_BUFFERED_REAL_BYTES);
    let mut read_buffer = [0_u8; 8 * 1024];
    let mut frame_index = 0_usize;
    let mut input_closed = false;
    let mut ticks = interval_at(Instant::now() + profile.tick_interval(), profile.tick_interval());
    ticks.set_missed_tick_behavior(MissedTickBehavior::Skip);

    loop {
        let read_capacity = read_buffer.len().min(MAX_BUFFERED_REAL_BYTES - pending.len());
        tokio::select! {
            biased;
            _ = ticks.tick() => {
                if input_closed && pending.is_empty() {
                    peer_writer.shutdown().await?;
                    // Release publishes the peer half-close before shutdown's
                    // application-side Acquire returns success.
                    flush_state.outbound_closed.store(true, Ordering::Release);
                    flush_state.waker.wake();
                    return Ok(());
                }

                let frame_size = profile.frame_sizes()[frame_index % profile.frame_sizes().len()];
                frame_index = frame_index.wrapping_add(1);
                let real_bytes = pending.len().min(frame_size - FRAME_HEADER_BYTES);
                let frame = encode_frame(frame_size, real_bytes, &mut pending)?;
                peer_writer.write_all(&frame).await?;
                peer_writer.flush().await?;
                stats.record_transmitted(real_bytes, frame_size);
                // Release publishes completion of the peer flush before the
                // application-side Acquire observes its acknowledgement.
                flush_state.transmitted_real_bytes.fetch_add(real_bytes as u64, Ordering::Release);
                flush_state.waker.wake();
            }
            read_result = application_reader.read(&mut read_buffer[..read_capacity]), if !input_closed && read_capacity > 0 => {
                let read_bytes = read_result?;
                if read_bytes == 0 {
                    input_closed = true;
                } else {
                    pending.extend(read_buffer[..read_bytes].iter().copied());
                }
            }
        }
    }
}

/// Decodes framed records and writes only real bytes to the application side.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation may discard a partially
/// read frame. Its owning [`JoinSet`] cancels it only when the complete shaped
/// session reaches a terminal state.
async fn pump_inbound<R>(
    peer_reader: &mut ReadHalf<R>,
    application_writer: &mut WriteHalf<DuplexStream>,
    profile: TrafficShapeProfile,
    stats: TrafficShapeStats,
) -> io::Result<()>
where
    R: AsyncRead + Unpin,
{
    loop {
        let mut header = [0_u8; FRAME_HEADER_BYTES];
        let first_header_bytes = peer_reader.read(&mut header).await?;
        if first_header_bytes == 0 {
            application_writer.shutdown().await?;
            return Ok(());
        }
        if first_header_bytes < FRAME_HEADER_BYTES {
            peer_reader.read_exact(&mut header[first_header_bytes..]).await?;
        }

        let frame_size = usize::from(u16::from_be_bytes([header[0], header[1]]));
        let real_bytes = usize::from(u16::from_be_bytes([header[2], header[3]]));
        validate_frame_header(profile, frame_size, real_bytes)?;

        let mut frame_body = vec![0_u8; frame_size - FRAME_HEADER_BYTES];
        peer_reader.read_exact(&mut frame_body).await?;
        if real_bytes > 0 {
            application_writer.write_all(&frame_body[..real_bytes]).await?;
            application_writer.flush().await?;
        }
        stats.record_received(real_bytes, frame_size);
    }
}

fn encode_frame(frame_size: usize, real_bytes: usize, pending: &mut VecDeque<u8>) -> io::Result<Vec<u8>> {
    let encoded_frame_size = u16::try_from(frame_size).map_err(|_| invalid_frame("frame size exceeds u16"))?;
    let encoded_real_bytes = u16::try_from(real_bytes).map_err(|_| invalid_frame("payload size exceeds u16"))?;
    let mut frame = vec![0_u8; frame_size];
    frame[..2].copy_from_slice(&encoded_frame_size.to_be_bytes());
    frame[2..FRAME_HEADER_BYTES].copy_from_slice(&encoded_real_bytes.to_be_bytes());
    for slot in &mut frame[FRAME_HEADER_BYTES..FRAME_HEADER_BYTES + real_bytes] {
        *slot = pending.pop_front().ok_or_else(|| invalid_frame("payload queue underflow"))?;
    }
    Ok(frame)
}

fn validate_frame_header(profile: TrafficShapeProfile, frame_size: usize, real_bytes: usize) -> io::Result<()> {
    if !profile.frame_sizes().contains(&frame_size) {
        return Err(invalid_frame("frame size is not valid for the selected profile"));
    }
    if real_bytes > frame_size - FRAME_HEADER_BYTES {
        return Err(invalid_frame("payload length exceeds frame capacity"));
    }
    Ok(())
}

fn invalid_frame(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

fn copy_io_error(error: &io::Error) -> io::Error {
    io::Error::new(error.kind(), error.to_string())
}
