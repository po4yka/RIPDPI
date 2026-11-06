use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use url::Url;

use super::http1_exchange::exchange_binary_post_over_stream;
use super::{MAX_DOH_HEADER_BYTES, MAX_DOH_RESPONSE_BYTES};
use crate::transport::DNS_MESSAGE_MEDIA_TYPE;
use crate::types::EncryptedDnsError;

/// # Cancel safety:
/// cancel-safe: the test owns both ends of the connection.
async fn exchange(response: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
    let (mut client, mut server) = tokio::io::duplex(response.len() + 4096);
    let url = Url::parse("https://[::1]:8443/dns-query?key=value").expect("URL");
    let peer = async {
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            request.push(server.read_u8().await.expect("request byte"));
        }
        let request = String::from_utf8(request).expect("request text").to_ascii_lowercase();
        assert!(request.starts_with("post /dns-query?key=value http/1.1\r\n"));
        assert!(request.contains("\r\nhost: [::1]:8443\r\n"));
        let mut body = [0; 3];
        server.read_exact(&mut body).await.expect("request body");
        assert_eq!(&body, b"dns");
        let _ = server.write_all(response).await;
        let _ = server.shutdown().await;
    };
    let call = exchange_binary_post_over_stream(
        &mut client,
        &url,
        b"dns",
        DNS_MESSAGE_MEDIA_TYPE,
        DNS_MESSAGE_MEDIA_TYPE,
        Duration::from_secs(2),
    );
    let (_, result) = tokio::join!(peer, call);
    result
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all connection state.
async fn response_framing_skips_informational_heads() {
    let response =
        b"HTTP/1.1 103 Early Hints\r\nLink: </dns-query>\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\ndns";
    assert_eq!(exchange(response).await.expect("final response"), b"dns");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all connection state.
async fn response_framing_rejects_conflicting_lengths_and_transfer_encoding() {
    for response in [
        &b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 3\r\n\r\ndns"[..],
        &b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\n\r\n3\r\ndns\r\n0\r\n\r\n"[..],
        &b"HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n3\r\ndns\r\n0\r\n\r\n"[..],
    ] {
        assert!(exchange(response).await.is_err());
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all connection state.
async fn response_framing_requires_complete_chunk_trailers() {
    for body in ["3\r\ndns\r\n0\r\n", "3\r\ndnsXX0\r\n\r\n", "3\r\ndns\r\n0\r\nBad Trailer: x\r\n\r\n"] {
        assert!(
            exchange(format!("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n{body}").as_bytes()).await.is_err()
        );
    }
    let response =
        b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=value\r\ndns\r\n0\r\nX-End: yes\r\n\r\n";
    assert_eq!(exchange(response).await.expect("chunked response"), b"dns");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all connection state.
async fn response_framing_enforces_body_and_header_limits() {
    let oversized_length = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n", MAX_DOH_RESPONSE_BYTES + 1);
    let error = exchange(oversized_length.as_bytes()).await.expect_err("oversized declared body");
    assert!(
        matches!(error, EncryptedDnsError::Request(message) if message.contains("Content-Length exceeds maximum size"))
    );
    for size in ["10000", "ffffffffffffffff"] {
        let response = format!("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n{size}\r\n");
        assert!(exchange(response.as_bytes()).await.is_err());
    }
    let response = format!(
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10000\r\n{}\r\n0\r\n\r\n",
        "x".repeat(MAX_DOH_RESPONSE_BYTES + 1)
    );
    let error = exchange(response.as_bytes()).await.expect_err("oversized received body");
    assert!(matches!(error, EncryptedDnsError::Request(message) if message.contains("body exceeds maximum size")));
    let response = format!("HTTP/1.1 200 OK\r\nX-Fill: {}\r\n\r\n", "a".repeat(MAX_DOH_HEADER_BYTES));
    assert!(exchange(response.as_bytes()).await.is_err());
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the helper owns all connection state.
async fn response_framing_handles_eof_status_and_truncation() {
    assert_eq!(exchange(b"HTTP/1.1 200 OK\r\n\r\ndns").await.expect("EOF body"), b"dns");
    assert_eq!(exchange(b"HTTP/1.1 204 No Content\r\n\r\n").await.expect("empty body"), b"");
    assert!(matches!(
        exchange(b"HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n").await,
        Err(EncryptedDnsError::HttpStatus(_))
    ));
    assert!(exchange(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndns").await.is_err());
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns both stream ends and drops them on cancellation.
async fn response_framing_times_out_without_response() {
    let (mut client, _server) = tokio::io::duplex(4096);
    let error = exchange_binary_post_over_stream(
        &mut client,
        &Url::parse("https://example.test/dns-query").expect("URL"),
        b"dns",
        DNS_MESSAGE_MEDIA_TYPE,
        DNS_MESSAGE_MEDIA_TYPE,
        Duration::from_millis(10),
    )
    .await
    .expect_err("response deadline");
    assert!(matches!(error, EncryptedDnsError::Request(message) if message.contains("timed out")));
}
