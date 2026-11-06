use super::{FronterError, headers};
use http_body_util::BodyExt;
use hyper::body::{Body, Incoming};
use std::io::Read;

const MAX_RESPONSE_BODY_BYTES: usize = 32 * 1024 * 1024;

pub(super) struct HttpResponse {
    pub(super) status: u16,
    pub(super) headers: Vec<(String, String)>,
    pub(super) body: Vec<u8>,
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation discards consumed frames; the owner drops the connection.
pub(super) async fn read(response: ::http::Response<Incoming>) -> Result<HttpResponse, FronterError> {
    read_with_body_limit(response, MAX_RESPONSE_BODY_BYTES).await
}

/// # Cancel safety:
/// NOT cancel-safe: partial body data is local; the caller must drop its connection on cancellation.
async fn read_with_body_limit(
    response: ::http::Response<Incoming>,
    limit: usize,
) -> Result<HttpResponse, FronterError> {
    let (parts, mut incoming) = response.into_parts();
    headers::validate_transfer_encoding(&parts.headers)?;
    if parts.headers.contains_key(::http::header::TRANSFER_ENCODING)
        && parts.headers.contains_key(::http::header::CONTENT_LENGTH)
    {
        return Err(FronterError::BadResponse("ambiguous response framing".into()));
    }
    if incoming.size_hint().upper().is_some_and(|size| size > limit as u64) {
        return Err(response_body_too_large());
    }
    let headers = parts
        .headers
        .iter()
        .map(|(key, value)| {
            value
                .to_str()
                .map(|value| (key.to_string(), value.to_string()))
                .map_err(|_| FronterError::BadResponse("invalid response header value".into()))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let mut body = Vec::new();
    while let Some(frame) = incoming.frame().await {
        let frame = frame.map_err(|error| FronterError::BadResponse(error.to_string()))?;
        if let Ok(data) = frame.into_data() {
            append_body_bytes(&mut body, &data, limit)?;
        }
    }
    if headers::value(&headers, "content-encoding").is_some_and(|value| value.eq_ignore_ascii_case("gzip")) {
        body = decode_gzip(&body, limit)?;
    }
    Ok(HttpResponse { status: parts.status.as_u16(), headers, body })
}

fn validate_body_length(length: usize, max_body_bytes: usize) -> Result<(), FronterError> {
    if length > max_body_bytes {
        return Err(response_body_too_large());
    }
    Ok(())
}

fn append_body_bytes(buffer: &mut Vec<u8>, bytes: &[u8], max_body_bytes: usize) -> Result<(), FronterError> {
    let new_length = buffer.len().checked_add(bytes.len()).ok_or_else(response_body_too_large)?;
    validate_body_length(new_length, max_body_bytes)?;
    buffer.extend_from_slice(bytes);
    Ok(())
}

fn response_body_too_large() -> FronterError {
    FronterError::BadResponse("response body too large".to_string())
}

fn decode_gzip(data: &[u8], decoded_limit: usize) -> Result<Vec<u8>, FronterError> {
    let decoder = flate2::read::GzDecoder::new(data);
    let limit_plus_one = decoded_limit.checked_add(1).ok_or_else(response_body_too_large)?;
    let read_limit = u64::try_from(limit_plus_one).map_err(|_| response_body_too_large())?;
    let mut limited = decoder.take(read_limit);
    let mut output = Vec::new();
    limited.read_to_end(&mut output)?;
    validate_body_length(output.len(), decoded_limit)?;
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::Bytes;
    use http_body_util::Full;
    use hyper_util::rt::TokioIo;
    use std::io::Write;
    use std::time::Duration;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// # Cancel safety:
    /// cancel-safe: all endpoints and futures are owned by this test.
    async fn exchange(parts: &[&[u8]], delay: Duration, limit: usize) -> Result<HttpResponse, FronterError> {
        let (client, mut peer) = tokio::io::duplex(4096);
        let server = async move {
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n") {
                request.push(peer.read_u8().await.expect("request header byte"));
            }
            for (index, part) in parts.iter().enumerate() {
                if index != 0 {
                    tokio::time::sleep(delay).await;
                }
                if peer.write_all(part).await.is_err() {
                    break;
                }
            }
        };
        let client = async {
            let (mut sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(client)).await.unwrap();
            let request = async {
                let request = ::http::Request::builder().uri("/").body(Full::new(Bytes::new())).unwrap();
                let response =
                    sender.send_request(request).await.map_err(|e| FronterError::BadResponse(e.to_string()))?;
                read_with_body_limit(response, limit).await
            };
            tokio::pin!(request);
            tokio::pin!(connection);
            tokio::select! {
                result = &mut request => result,
                _ = &mut connection => request.await,
            }
        };
        let (result, ()) = tokio::join!(client, server);
        result
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns the full exchange.
    #[tokio::test(start_paused = true)]
    async fn close_delimited_body_waits_for_eof_after_a_pause() {
        let response =
            exchange(&[b"HTTP/1.1 200 OK\r\n\r\nhello", b" world"], Duration::from_millis(900), 32).await.unwrap();
        assert_eq!(response.body, b"hello world");
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns the full exchange.
    #[tokio::test]
    async fn invalid_or_truncated_framing_is_rejected() {
        for wire in [
            &b"HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"[..],
            &b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX0\r\n\r\n"[..],
            b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabc",
            b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabc",
            b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
        ] {
            assert!(exchange(&[wire], Duration::ZERO, 32).await.is_err(), "{wire:?}");
        }
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns the full exchange.
    #[tokio::test]
    async fn fixed_chunked_and_close_delimited_bodies_obey_limits() {
        for wire in [
            &b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"[..],
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;ext=yes\r\nhello\r\n0\r\nX-Trailer: yes\r\n\r\n",
            b"HTTP/1.1 200 OK\r\n\r\nhello",
        ] {
            assert_eq!(exchange(&[wire], Duration::ZERO, 5).await.unwrap().body, b"hello");
            assert!(exchange(&[wire], Duration::ZERO, 4).await.is_err());
        }
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns the full exchange.
    #[tokio::test]
    async fn gzip_decoding_obeys_expanded_limit() {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&[b'a'; 33]).unwrap();
        let compressed = encoder.finish().unwrap();
        let mut wire =
            format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nContent-Encoding: gzip\r\n\r\n", compressed.len())
                .into_bytes();
        wire.extend(compressed);
        assert!(exchange(&[&wire], Duration::ZERO, 32).await.is_err());
        assert_eq!(exchange(&[&wire], Duration::ZERO, 33).await.unwrap().body.len(), 33);
    }
}
