use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::sync::{Notify, OwnedSemaphorePermit, Semaphore};

use super::wrap_tcp_stream;
use crate::config::FinalmaskConfig;

const DEADLINE: Duration = Duration::from_secs(2);

fn mask_config() -> FinalmaskConfig {
    FinalmaskConfig {
        r#type: "fragment".to_string(),
        fragment_packets: 2,
        fragment_min_bytes: 1,
        fragment_max_bytes: 4,
        ..FinalmaskConfig::default()
    }
}

struct FaultIo {
    read_error: bool,
    write_error: bool,
    write_started: Arc<Notify>,
    _capacity: OwnedSemaphorePermit,
}

impl AsyncRead for FaultIo {
    fn poll_read(self: Pin<&mut Self>, _: &mut Context<'_>, _: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if self.read_error {
            Poll::Ready(Err(io::Error::new(io::ErrorKind::ConnectionReset, "downlink fault")))
        } else {
            Poll::Pending
        }
    }
}

impl AsyncWrite for FaultIo {
    fn poll_write(self: Pin<&mut Self>, _: &mut Context<'_>, _: &[u8]) -> Poll<io::Result<usize>> {
        self.write_started.notify_one();
        if self.write_error {
            Poll::Ready(Err(io::Error::new(io::ErrorKind::BrokenPipe, "uplink fault")))
        } else {
            Poll::Pending
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

/// # Cancel safety:
/// cancel-safe: all partial test I/O and its carrier are discarded on cancellation.
#[tokio::test]
async fn uplink_error_reaches_reader_and_releases_pending_downlink() {
    let capacity = Arc::new(Semaphore::new(1));
    let io = FaultIo {
        read_error: false,
        write_error: true,
        write_started: Arc::new(Notify::new()),
        _capacity: capacity.clone().acquire_owned().await.expect("capacity"),
    };
    let mut stream = wrap_tcp_stream(io, &mask_config()).expect("mask");
    stream.write_all(b"TLS").await.expect("buffered write");
    let mut byte = [0];
    let error = tokio::time::timeout(DEADLINE, stream.read(&mut byte))
        .await
        .expect("fatal write must wake reader")
        .expect_err("fatal write must fail read");
    assert_eq!(error.kind(), io::ErrorKind::BrokenPipe);
    assert_eq!(error.to_string(), "uplink fault");
    let _replacement = tokio::time::timeout(DEADLINE, capacity.acquire_owned())
        .await
        .expect("pending sibling released carrier")
        .expect("replacement capacity");
}

/// # Cancel safety:
/// cancel-safe: all partial test I/O and its carrier are discarded on cancellation.
#[tokio::test]
async fn downlink_error_reaches_writer_and_releases_carrier() {
    let capacity = Arc::new(Semaphore::new(1));
    let io = FaultIo {
        read_error: true,
        write_error: false,
        write_started: Arc::new(Notify::new()),
        _capacity: capacity.clone().acquire_owned().await.expect("capacity"),
    };
    let mut stream = wrap_tcp_stream(io, &mask_config()).expect("mask");
    let _replacement = tokio::time::timeout(DEADLINE, capacity.acquire_owned())
        .await
        .expect("read error must release carrier")
        .expect("replacement capacity");
    let error = stream.write_all(b"TLS").await.expect_err("fatal read must fail write");
    assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
    assert_eq!(error.to_string(), "downlink fault");
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards both halves if any operation is cancelled.
#[tokio::test]
async fn uplink_half_close_keeps_downlink_open() {
    let (client, mut peer) = tokio::io::duplex(1024);
    let mut stream = wrap_tcp_stream(client, &mask_config()).expect("mask");
    stream.write_all(b"request").await.expect("request");
    stream.shutdown().await.expect("half close");
    let mut request = Vec::new();
    tokio::time::timeout(DEADLINE, peer.read_to_end(&mut request)).await.expect("request EOF").expect("request read");
    assert_eq!(request, b"request");
    peer.write_all(b"response").await.expect("response");
    peer.shutdown().await.expect("response EOF");
    let mut response = Vec::new();
    tokio::time::timeout(DEADLINE, stream.read_to_end(&mut response))
        .await
        .expect("response completed")
        .expect("response read");
    assert_eq!(response, b"response");
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards both halves if any operation is cancelled.
#[tokio::test]
async fn downlink_half_close_keeps_uplink_open() {
    let (client, mut peer) = tokio::io::duplex(1024);
    let mut stream = wrap_tcp_stream(client, &mask_config()).expect("mask");
    peer.shutdown().await.expect("peer half close");
    let mut byte = [0];
    assert_eq!(tokio::time::timeout(DEADLINE, stream.read(&mut byte)).await.expect("peer EOF").expect("read"), 0);
    stream.write_all(b"request").await.expect("uplink remains open");
    stream.shutdown().await.expect("uplink EOF");
    let mut request = Vec::new();
    tokio::time::timeout(DEADLINE, peer.read_to_end(&mut request)).await.expect("uplink completed").expect("read");
    assert_eq!(request, b"request");
}

/// # Cancel safety:
/// cancel-safe: dropping the stream cancels the pending pump and releases its owned capacity.
#[tokio::test]
async fn dropping_carrier_cancels_pending_write() {
    let capacity = Arc::new(Semaphore::new(1));
    let write_started = Arc::new(Notify::new());
    let io = FaultIo {
        read_error: false,
        write_error: false,
        write_started: write_started.clone(),
        _capacity: capacity.clone().acquire_owned().await.expect("capacity"),
    };
    let mut stream = wrap_tcp_stream(io, &mask_config()).expect("mask");
    stream.write_all(b"TLS").await.expect("buffered write");
    tokio::time::timeout(DEADLINE, write_started.notified()).await.expect("underlying write pending");
    drop(stream);
    let _replacement = tokio::time::timeout(DEADLINE, capacity.acquire_owned())
        .await
        .expect("drop released pending write")
        .expect("replacement capacity");
}
