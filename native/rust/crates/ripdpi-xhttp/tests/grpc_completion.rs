use ripdpi_xhttp::{GrpcTransport, GrpcTransportConfig};
use std::io;
use tokio::io::AsyncReadExt;

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns all stream endpoints.
async fn missing_carrier_completion_is_not_success() {
    let (mut transport, wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    drop(wire);
    let mut bytes = Vec::new();
    assert_eq!(
        transport.read_to_end(&mut bytes).await.expect_err("missing status").kind(),
        io::ErrorKind::UnexpectedEof
    );
}

use std::pin::Pin;
use std::task::{Context, Poll, Waker};
use tokio::io::{AsyncRead, AsyncWriteExt, ReadBuf};

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: all endpoints, errors and buffers are owned by this test.
async fn carrier_errors_follow_received_payload() {
    for kind in [io::ErrorKind::PermissionDenied, io::ErrorKind::ConnectionReset, io::ErrorKind::InvalidData] {
        let (mut transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
        wire.downstream.write_all(b"\x00\x00\x00\x00\x03\x0a\x01Z").await.expect("payload");
        wire.downstream.finish_response(Err(io::Error::new(kind, "carrier terminal error"))).await.expect("finish");
        let mut bytes = Vec::new();
        let error = transport.read_to_end(&mut bytes).await.expect_err("carrier failure");
        assert_eq!(bytes, b"Z");
        assert_eq!(error.kind(), kind);
        assert_eq!(error.to_string(), "carrier terminal error");
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: terminal state stays in the transport after a canceled read.
async fn data_eof_waits_for_explicit_completion() {
    let (mut transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    wire.downstream.shutdown().await.expect("DATA EOF");
    let mut byte = [0];
    let mut read = ReadBuf::new(&mut byte);
    let mut cx = Context::from_waker(Waker::noop());
    assert!(matches!(Pin::new(&mut transport).poll_read(&mut cx, &mut read), Poll::Pending));
    wire.downstream.finish_response(Ok(())).await.expect("OK trailers");
    assert_eq!(transport.read(&mut byte).await.expect("successful EOF"), 0);
    assert_eq!(
        wire.downstream.finish_response(Ok(())).await.expect_err("duplicate").kind(),
        io::ErrorKind::AlreadyExists
    );
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns partial frames and endpoints.
async fn malformed_frames_take_precedence_over_carrier_result() {
    let (mut transport, mut wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    wire.downstream.write_all(&[1, 0, 0, 0, 0]).await.expect("bad flag");
    wire.downstream
        .finish_response(Err(io::Error::new(io::ErrorKind::PermissionDenied, "denied")))
        .await
        .expect("finish");
    let mut byte = [0];
    assert_eq!(transport.read(&mut byte).await.expect_err("framing failure").kind(), io::ErrorKind::InvalidData);
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: spawned pumps own their endpoints and are joined by this test.
async fn carrier_can_own_upload_and_download_in_separate_tasks() {
    let (mut transport, wire) = GrpcTransport::new(GrpcTransportConfig::new("local", "gun"));
    let upload = tokio::spawn(async move {
        let mut upstream = wire.upstream;
        let mut bytes = Vec::new();
        upstream.read_to_end(&mut bytes).await.expect("upload");
        bytes
    });
    let download = tokio::spawn(async move {
        let mut downstream = wire.downstream;
        downstream.write_all(b"\x00\x00\x00\x00\x03\x0a\x01Z").await.expect("DATA");
        downstream.finish_response(Ok(())).await.expect("status OK");
    });
    transport.write_all(b"A").await.expect("write");
    transport.shutdown().await.expect("upload EOF");
    let mut bytes = Vec::new();
    transport.read_to_end(&mut bytes).await.expect("download");
    assert_eq!(bytes, b"Z");
    assert_eq!(upload.await.expect("upload task"), b"\x00\x00\x00\x00\x03\x0a\x01A");
    download.await.expect("download task");
}
