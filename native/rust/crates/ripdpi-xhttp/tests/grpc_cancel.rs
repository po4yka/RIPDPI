use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, Waker};

use ripdpi_xhttp::{GrpcTransport, GrpcTransportConfig};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt, ReadBuf};

#[test]
fn transport_construction_needs_no_runtime() {
    let (transport, wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    drop((transport, wire));
}

/// # Cancel safety:
/// cancel-safe: all endpoints are test-owned and dropped on cancellation.
async fn dropped_transport_closes_idle_wire() {
    let (transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    drop(transport);
    let mut byte = [0];
    assert_eq!(wire.upstream.read(&mut byte).await.expect("upload closed"), 0);
    assert_eq!(wire.downstream.write(b"late").await.expect_err("download closed").kind(), io::ErrorKind::BrokenPipe);
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all endpoints.
async fn drop_closes_idle_wire_current_thread() {
    dropped_transport_closes_idle_wire().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
/// # Cancel safety:
/// cancel-safe: the helper owns all endpoints.
async fn drop_closes_idle_wire_multi_thread() {
    dropped_transport_closes_idle_wire().await;
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: partial codec state belongs to the transport.
async fn canceled_read_preserves_partial_frame() {
    let (mut transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    wire.downstream.write_all(&[0, 0, 0]).await.expect("prefix");
    let mut byte = [0];
    let mut read = ReadBuf::new(&mut byte);
    let mut cx = Context::from_waker(Waker::noop());
    assert!(matches!(Pin::new(&mut transport).poll_read(&mut cx, &mut read), Poll::Pending));
    wire.downstream.write_all(b"\x00\x07\x0a\x05hello").await.expect("tail");
    let mut payload = [0; 5];
    transport.read_exact(&mut payload).await.expect("payload");
    assert_eq!(&payload, b"hello");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: codec and partial read state stay in the owned transport.
async fn empty_hunks_yield_without_eof_and_zero_reads_do_not_consume() {
    let (mut transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    wire.downstream.write_all(&[0; 500]).await.expect("100 empty hunks");
    wire.downstream.write_all(b"\x00\x00\x00\x00\x03\x0a\x01Z").await.expect("payload");
    let mut cx = Context::from_waker(Waker::noop());
    let mut empty = ReadBuf::new(&mut []);
    assert!(matches!(Pin::new(&mut transport).poll_read(&mut cx, &mut empty), Poll::Ready(Ok(()))));
    let mut byte = [0];
    let mut read = ReadBuf::new(&mut byte);
    assert!(matches!(Pin::new(&mut transport).poll_read(&mut cx, &mut read), Poll::Pending));
    transport.read_exact(&mut byte).await.expect("after yield");
    assert_eq!(&byte, b"Z");
}
