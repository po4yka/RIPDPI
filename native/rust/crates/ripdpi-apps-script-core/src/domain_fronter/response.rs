use std::io::Read;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::time::timeout;

use super::FronterError;
use super::headers;

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

    let mut body = buffer[headers_end + 4..].to_vec();
    let content_length = headers::value(&headers, "content-length").and_then(|value| value.parse::<usize>().ok());
    let is_chunked = headers::value(&headers, "transfer-encoding")
        .is_some_and(|value| value.to_ascii_lowercase().contains("chunked"));

    if is_chunked {
        body = read_chunked(stream, body).await?;
    } else if let Some(content_length) = content_length {
        while body.len() < content_length {
            let wanted = (content_length - body.len()).min(scratch.len());
            let read = timeout(Duration::from_secs(10), stream.read(&mut scratch[..wanted]))
                .await
                .map_err(|_| FronterError::Timeout)??;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&scratch[..read]);
        }
    } else {
        loop {
            match timeout(Duration::from_millis(750), stream.read(&mut scratch)).await {
                Ok(Ok(0)) => break,
                Ok(Ok(read)) => body.extend_from_slice(&scratch[..read]),
                Ok(Err(error)) => return Err(error.into()),
                Err(_) => break,
            }
        }
    }

    if headers::value(&headers, "content-encoding").is_some_and(|value| value.eq_ignore_ascii_case("gzip")) {
        body = decode_gzip(&body).map_err(FronterError::Io)?;
    }

    Ok(HttpResponse { status, headers, body })
}

// NOT cancel-safe: decodes chunks into an output buffer across many reads;
// cancellation discards partially decoded data already taken from the stream.
async fn read_chunked<S>(stream: &mut S, mut buffer: Vec<u8>) -> Result<Vec<u8>, FronterError>
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
        fill_buffer(stream, &mut buffer, &mut scratch, size + 2).await?;
        output.extend_from_slice(&buffer[..size]);
        buffer.drain(..size + 2);
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
            let line = buffer[..position].to_vec();
            buffer.drain(..position + 2);
            return Ok(line);
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

fn decode_gzip(data: &[u8]) -> std::io::Result<Vec<u8>> {
    let mut decoder = flate2::read::GzDecoder::new(data);
    let mut output = Vec::new();
    decoder.read_to_end(&mut output)?;
    Ok(output)
}
