use tokio::io::AsyncRead;

use super::MAX_DOH_RESPONSE_BYTES;
use super::http1_response::read_more_doh_bytes;
use crate::types::EncryptedDnsError;

pub(super) async fn read_chunked_doh_body<S>(stream: &mut S, mut buffer: Vec<u8>) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + Unpin,
{
    let mut decoded = Vec::new();
    let mut cursor = 0usize;

    loop {
        let size_end = loop {
            if let Some(offset) = find_crlf(&buffer[cursor..]) {
                break cursor + offset;
            }
            if read_more_doh_bytes(stream, &mut buffer).await? == 0 {
                return Err(EncryptedDnsError::Request("chunked DoH response missing size delimiter".to_string()));
            }
        };
        let size_line = std::str::from_utf8(&buffer[cursor..size_end])
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        let size = usize::from_str_radix(size_line.split(';').next().unwrap_or_default().trim(), 16)
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        cursor = size_end + 2;

        if size == 0 {
            return Ok(decoded);
        }

        if decoded.len() + size > MAX_DOH_RESPONSE_BYTES {
            return Err(EncryptedDnsError::Request("chunked DoH response exceeds maximum size".to_string()));
        }

        while buffer.len() < cursor + size + 2 {
            if read_more_doh_bytes(stream, &mut buffer).await? == 0 {
                return Err(EncryptedDnsError::Request("chunked DoH response truncated".to_string()));
            }
        }

        decoded.extend_from_slice(&buffer[cursor..cursor + size]);
        cursor += size;
        if &buffer[cursor..cursor + 2] != b"\r\n" {
            return Err(EncryptedDnsError::Request("chunked DoH response missing chunk terminator".to_string()));
        }
        cursor += 2;

        buffer.drain(..cursor);
        cursor = 0;
    }
}

fn find_crlf(bytes: &[u8]) -> Option<usize> {
    bytes.windows(2).position(|window| window == b"\r\n")
}
