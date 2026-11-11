use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, Waker};

use ripdpi_xhttp::{GrpcTransport, GrpcTransportConfig};
use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// # Cancel safety:
/// cancel-safe: the test owns all endpoints and discards partial I/O on cancellation.
async fn flush_and_shutdown_drain_frames() {
    let (mut transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    let payload = vec![0x5a; 64 * 1024];
    transport.write_all(&payload).await.expect("write");
    let mut cx = Context::from_waker(Waker::noop());
    assert!(matches!(Pin::new(&mut transport).poll_flush(&mut cx), Poll::Pending));
    // A canceled flush retains its partial progress. Drain the wire concurrently.
    let reader = tokio::spawn(async move {
        let mut bytes = Vec::new();
        wire.upstream.read_to_end(&mut bytes).await.expect("wire read");
        bytes
    });
    transport.shutdown().await.expect("shutdown");
    assert_eq!(transport.write(b"late").await.expect_err("closed").kind(), io::ErrorKind::BrokenPipe);
    let bytes = reader.await.expect("reader task");
    // Independent protobuf wire expectation: field 1, 65536-byte varint.
    assert_eq!(&bytes[..9], &[0, 0, 1, 0, 4, 0x0a, 0x80, 0x80, 4]);
    assert_eq!(&bytes[9..], payload);
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all endpoint and buffer state.
async fn flush_drains_frames_current_thread() {
    flush_and_shutdown_drain_frames().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
/// # Cancel safety:
/// cancel-safe: the helper owns all endpoint and buffer state.
async fn flush_drains_frames_multi_thread() {
    flush_and_shutdown_drain_frames().await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the stream and discards it on cancellation.
async fn flush_returns_wire_failure() {
    let (mut transport, wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    transport.write_all(b"hello").await.expect("buffered write");
    drop(wire.upstream);
    assert_eq!(transport.flush().await.expect_err("wire closed").kind(), io::ErrorKind::BrokenPipe);
}
