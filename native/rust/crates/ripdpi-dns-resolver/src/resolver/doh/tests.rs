use tokio::io::AsyncWriteExt;

use super::chunked_body::read_chunked_doh_body;
use super::http1_response::{read_doh_body_with_content_length, read_doh_response_head};
use super::{MAX_DOH_HEADER_BYTES, MAX_DOH_RESPONSE_BYTES};
use crate::types::EncryptedDnsError;

#[tokio::test]
async fn read_doh_body_with_content_length_rejects_oversized_length() {
    let error = read_doh_body_with_content_length(&mut tokio::io::empty(), Vec::new(), MAX_DOH_RESPONSE_BYTES + 1)
        .await
        .expect_err("oversized Content-Length should fail");

    match error {
        EncryptedDnsError::Request(message) => {
            assert!(message.contains("Content-Length exceeds maximum size"));
        }
        other => panic!("expected request error, got {other:?}"),
    }
}

#[tokio::test]
async fn read_chunked_doh_body_rejects_chunk_larger_than_limit() {
    let error = read_chunked_doh_body(&mut tokio::io::empty(), b"10000\r\n".to_vec())
        .await
        .expect_err("oversized chunk should fail");

    match error {
        EncryptedDnsError::Request(message) => {
            assert!(message.contains("chunked DoH response exceeds maximum size"));
        }
        other => panic!("expected request error, got {other:?}"),
    }
}

#[tokio::test]
async fn read_doh_response_head_rejects_oversized_headers() {
    let oversized_headers = format!("HTTP/1.1 200 OK\r\nX-Fill: {}\r\n\r\n", "a".repeat(MAX_DOH_HEADER_BYTES),);
    let (mut client, mut server) = tokio::io::duplex(oversized_headers.len() + 16);
    let writer = tokio::spawn(async move {
        server.write_all(oversized_headers.as_bytes()).await.expect("write oversized headers");
        server.shutdown().await.expect("shutdown writer");
    });

    let error = read_doh_response_head(&mut client).await.expect_err("oversized headers should fail");
    writer.await.expect("writer task");

    match error {
        EncryptedDnsError::Request(message) => {
            assert!(message.contains("headers exceed maximum size"));
        }
        other => panic!("expected request error, got {other:?}"),
    }
}
