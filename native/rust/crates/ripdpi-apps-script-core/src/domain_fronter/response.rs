use std::io::Read;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::time::timeout;

use super::FronterError;
use super::headers;

const MAX_RESPONSE_BODY_BYTES: usize = 32 * 1024 * 1024;
const MAX_CHUNK_METADATA_LINE_BYTES: usize = 8 * 1024;

pub(super) struct HttpResponse {
    pub(super) status: u16,
    pub(super) headers: Vec<(String, String)>,
    pub(super) body: Vec<u8>,
}

// NOT cancel-safe: accumulates headers and body into local buffers across many
// timed reads; cancellation discards the partial response already consumed from
// the stream.
pub(super) async fn read<S>(stream: &mut S) -> Result<HttpResponse, FronterError>
where
    S: AsyncRead + Unpin,
{
    read_with_body_limit(stream, MAX_RESPONSE_BODY_BYTES).await
}

// NOT cancel-safe: accumulates headers and body into local buffers across many
// timed reads; cancellation discards the partial response already consumed from
// the stream.
async fn read_with_body_limit<S>(stream: &mut S, max_body_bytes: usize) -> Result<HttpResponse, FronterError>
where
    S: AsyncRead + Unpin,
{
    let mut buffer = Vec::with_capacity(8_192);
    let mut scratch = [0u8; 8_192];
    let headers_end = loop {
        let read =
            timeout(Duration::from_secs(10), stream.read(&mut scratch)).await.map_err(|_| FronterError::Timeout)??;
        if read == 0 {
            return Err(FronterError::BadResponse("connection closed before headers".to_string()));
        }
        buffer.extend_from_slice(&scratch[..read]);
        if let Some(position) = find_headers_end(&buffer) {
            break position;
        }
        if buffer.len() > 1024 * 1024 {
            return Err(FronterError::BadResponse("response headers too large".to_string()));
        }
    };

    let header_section = std::str::from_utf8(&buffer[..headers_end])
        .map_err(|_| FronterError::BadResponse("response headers are not utf-8".to_string()))?;
    let mut lines = header_section.split("\r\n");
    let status = parse_status_line(lines.next().unwrap_or_default())?;
    let mut headers = Vec::new();
    for line in lines {
        if let Some((key, value)) = line.split_once(':') {
            headers.push((key.trim().to_string(), value.trim().to_string()));
        }
    }

    let content_length = headers::value(&headers, "content-length").and_then(|value| value.parse::<usize>().ok());
    let is_chunked = headers::value(&headers, "transfer-encoding")
        .is_some_and(|value| value.to_ascii_lowercase().contains("chunked"));
    if !is_chunked && content_length.is_some_and(|length| length > max_body_bytes) {
        return Err(response_body_too_large());
    }
    let mut body = buffer[headers_end + 4..].to_vec();

    if is_chunked {
        body = read_chunked(stream, body, max_body_bytes).await?;
    } else if let Some(content_length) = content_length {
        validate_body_length(body.len(), max_body_bytes)?;
        while body.len() < content_length {
            let wanted = (content_length - body.len()).min(scratch.len());
            let read = timeout(Duration::from_secs(10), stream.read(&mut scratch[..wanted]))
                .await
                .map_err(|_| FronterError::Timeout)??;
            if read == 0 {
                return Err(FronterError::BadResponse(
                    "connection closed before declared response body length".to_string(),
                ));
            }
            append_body_bytes(&mut body, &scratch[..read], max_body_bytes)?;
        }
    } else {
        validate_body_length(body.len(), max_body_bytes)?;
        loop {
            match timeout(Duration::from_millis(750), stream.read(&mut scratch)).await {
                Ok(Ok(0)) => break,
                Ok(Ok(read)) => append_body_bytes(&mut body, &scratch[..read], max_body_bytes)?,
                Ok(Err(error)) => return Err(error.into()),
                Err(_) => break,
            }
        }
    }

    if headers::value(&headers, "content-encoding").is_some_and(|value| value.eq_ignore_ascii_case("gzip")) {
        body = decode_gzip(&body, max_body_bytes)?;
    }

    Ok(HttpResponse { status, headers, body })
}

// NOT cancel-safe: decodes chunks into an output buffer across many reads;
// cancellation discards partially decoded data already taken from the stream.
async fn read_chunked<S>(stream: &mut S, mut buffer: Vec<u8>, max_body_bytes: usize) -> Result<Vec<u8>, FronterError>
where
    S: AsyncRead + Unpin,
{
    let mut output = Vec::new();
    let mut scratch = [0u8; 8_192];
    loop {
        let line = read_crlf_line(stream, &mut buffer, &mut scratch).await?;
        if line.is_empty() {
            continue;
        }
        let line = std::str::from_utf8(&line)
            .map_err(|_| FronterError::BadResponse("invalid chunk size line".to_string()))?
            .trim()
            .to_string();
        let size = usize::from_str_radix(line.split(';').next().unwrap_or_default(), 16)
            .map_err(|_| FronterError::BadResponse(format!("invalid chunk size {line}")))?;
        if size == 0 {
            loop {
                if read_crlf_line(stream, &mut buffer, &mut scratch).await?.is_empty() {
                    return Ok(output);
                }
            }
        }
        validate_body_length(output.len().checked_add(size).ok_or_else(response_body_too_large)?, max_body_bytes)?;
        let framed_size = size.checked_add(2).ok_or_else(response_body_too_large)?;
        fill_buffer(stream, &mut buffer, &mut scratch, framed_size).await?;
        append_body_bytes(&mut output, &buffer[..size], max_body_bytes)?;
        buffer.drain(..framed_size);
    }
}

// NOT cancel-safe: appends timed reads into the caller-owned buffer until a CRLF
// is found; cancellation between the read await and the append loses bytes
// already pulled from the stream.
async fn read_crlf_line<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8]) -> Result<Vec<u8>, FronterError>
where
    S: AsyncRead + Unpin,
{
    loop {
        if let Some(position) = buffer.windows(2).position(|window| window == b"\r\n") {
            if position > MAX_CHUNK_METADATA_LINE_BYTES {
                return Err(FronterError::BadResponse("chunk metadata line too large".to_string()));
            }
            let line = buffer[..position].to_vec();
            buffer.drain(..position + 2);
            return Ok(line);
        }
        if buffer.len() > MAX_CHUNK_METADATA_LINE_BYTES {
            return Err(FronterError::BadResponse("chunk metadata line too large".to_string()));
        }
        let read =
            timeout(Duration::from_secs(10), stream.read(scratch)).await.map_err(|_| FronterError::Timeout)??;
        if read == 0 {
            return Err(FronterError::BadResponse("connection closed while decoding chunked body".to_string()));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
}

// NOT cancel-safe: reads until the caller-owned buffer holds `wanted` bytes;
// cancellation after a timed read completes but before the append loses bytes
// already consumed from the stream.
async fn fill_buffer<S>(
    stream: &mut S,
    buffer: &mut Vec<u8>,
    scratch: &mut [u8],
    wanted: usize,
) -> Result<(), FronterError>
where
    S: AsyncRead + Unpin,
{
    while buffer.len() < wanted {
        let read =
            timeout(Duration::from_secs(10), stream.read(scratch)).await.map_err(|_| FronterError::Timeout)??;
        if read == 0 {
            return Err(FronterError::BadResponse("connection closed while filling response buffer".to_string()));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
    Ok(())
}

fn parse_status_line(value: &str) -> Result<u16, FronterError> {
    let mut parts = value.split_whitespace();
    let _version = parts.next();
    let code = parts.next().ok_or_else(|| FronterError::BadResponse(format!("invalid status line {value}")))?;
    code.parse::<u16>().map_err(|_| FronterError::BadResponse(format!("invalid status code {code}")))
}

fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n")
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
    use std::collections::VecDeque;
    use std::io::Write as _;
    use std::pin::Pin;
    use std::task::{Context, Poll};

    use tokio::io::{AsyncRead, ReadBuf};

    use super::*;

    const TEST_BODY_LIMIT: usize = 32;

    struct MemoryReader {
        chunks: VecDeque<Vec<u8>>,
        reads: usize,
    }

    impl MemoryReader {
        fn new(chunks: impl IntoIterator<Item = Vec<u8>>) -> Self {
            Self { chunks: chunks.into_iter().collect(), reads: 0 }
        }
    }

    impl AsyncRead for MemoryReader {
        fn poll_read(
            mut self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            buf: &mut ReadBuf<'_>,
        ) -> Poll<std::io::Result<()>> {
            let Some(chunk) = self.chunks.pop_front() else {
                return Poll::Ready(Ok(()));
            };
            self.reads += 1;
            let read = chunk.len().min(buf.remaining());
            buf.put_slice(&chunk[..read]);
            if read < chunk.len() {
                self.chunks.push_front(chunk[read..].to_vec());
            }
            Poll::Ready(Ok(()))
        }
    }

    fn assert_bad_response(result: Result<HttpResponse, FronterError>, expected: &str) {
        match result {
            Err(FronterError::BadResponse(message)) => assert_eq!(message, expected),
            Err(error) => panic!("expected bad response {expected:?}, got {error}"),
            Ok(_) => panic!("expected bad response {expected:?}, got success"),
        }
    }

    #[tokio::test]
    async fn rejects_fixed_content_length_above_limit_before_body_read() {
        let headers = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n", TEST_BODY_LIMIT + 1).into_bytes();
        let mut stream = MemoryReader::new([headers, vec![b'a'; TEST_BODY_LIMIT + 1]]);

        let result = read_with_body_limit(&mut stream, TEST_BODY_LIMIT).await;

        assert_bad_response(result, "response body too large");
        assert_eq!(stream.reads, 1);
    }

    #[tokio::test]
    async fn accepts_fixed_content_length_at_limit() {
        let mut response = format!("HTTP/1.1 200 OK\r\nContent-Length: {TEST_BODY_LIMIT}\r\n\r\n").into_bytes();
        response.extend_from_slice(&[b'a'; TEST_BODY_LIMIT]);
        let mut stream = MemoryReader::new([response]);

        let response =
            read_with_body_limit(&mut stream, TEST_BODY_LIMIT).await.expect("response at the body limit must succeed");

        assert_eq!(response.body, vec![b'a'; TEST_BODY_LIMIT]);
    }

    #[tokio::test]
    async fn rejects_truncated_fixed_content_length_at_eof() {
        let mut stream = MemoryReader::new([b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabc".to_vec()]);

        let result = read_with_body_limit(&mut stream, TEST_BODY_LIMIT).await;

        assert_bad_response(result, "connection closed before declared response body length");
    }

    #[tokio::test]
    async fn rejects_chunk_exceeding_remaining_limit_before_payload_read() {
        let mut first = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".to_vec();
        first.extend_from_slice(b"3\r\nabc\r\n");
        let mut stream = MemoryReader::new([first, b"2\r\n".to_vec(), b"de\r\n0\r\n\r\n".to_vec()]);

        let result = read_with_body_limit(&mut stream, 4).await;

        assert_bad_response(result, "response body too large");
        assert_eq!(stream.reads, 2);
    }

    #[tokio::test]
    async fn rejects_close_delimited_body_above_limit() {
        let mut response = b"HTTP/1.1 200 OK\r\n\r\n".to_vec();
        response.extend_from_slice(&[b'a'; TEST_BODY_LIMIT + 1]);
        let mut stream = MemoryReader::new([response]);

        let result = read_with_body_limit(&mut stream, TEST_BODY_LIMIT).await;

        assert_bad_response(result, "response body too large");
    }

    #[tokio::test]
    async fn rejects_gzip_decoded_body_above_limit() {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&[b'a'; TEST_BODY_LIMIT + 1]).expect("in-memory gzip input must write");
        let compressed = encoder.finish().expect("in-memory gzip stream must finish");
        assert!(compressed.len() <= TEST_BODY_LIMIT);
        let mut response =
            format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nContent-Encoding: gzip\r\n\r\n", compressed.len())
                .into_bytes();
        response.extend_from_slice(&compressed);
        let mut stream = MemoryReader::new([response]);

        let result = read_with_body_limit(&mut stream, TEST_BODY_LIMIT).await;

        assert_bad_response(result, "response body too large");
    }

    #[tokio::test]
    async fn rejects_unterminated_chunk_metadata_line_above_limit() {
        let mut response = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".to_vec();
        response.extend_from_slice(&vec![b'1'; MAX_CHUNK_METADATA_LINE_BYTES + 1]);
        let mut stream = MemoryReader::new([response]);

        let result = read_with_body_limit(&mut stream, TEST_BODY_LIMIT).await;

        assert_bad_response(result, "chunk metadata line too large");
    }
}
