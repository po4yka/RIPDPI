use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use bytes::{Bytes, BytesMut};
use rand::Rng;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, ReadBuf, sink};

use crate::varint::{put_varint, read_varint};

pub struct DuplexStream {
    send: quinn::SendStream,
    recv: quinn::RecvStream,
    // A hopping stream owns its carrier schedule even if the handle is dropped.
    // Scalar sessions retain their existing stream ownership behavior.
    _client: Option<Arc<crate::client::ClientInner>>,
}

impl DuplexStream {
    pub(crate) fn new(
        send: quinn::SendStream,
        recv: quinn::RecvStream,
        client: Option<Arc<crate::client::ClientInner>>,
    ) -> Self {
        Self { send, recv, _client: client }
    }
}

impl AsyncRead for DuplexStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.recv).poll_read(cx, buf)
    }
}

impl AsyncWrite for DuplexStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.send).poll_write(cx, buf).map_err(io::Error::other)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_shutdown(cx)
    }
}

pub(crate) fn build_tcp_request(address: &str, padding_len: usize) -> Bytes {
    let mut buffer = BytesMut::with_capacity(address.len() + padding_len + 32);
    put_varint(0x401, &mut buffer);
    put_varint(address.len() as u64, &mut buffer);
    buffer.extend_from_slice(address.as_bytes());
    put_varint(padding_len as u64, &mut buffer);
    if padding_len > 0 {
        let mut padding = vec![0u8; padding_len];
        rand::rng().fill_bytes(&mut padding);
        buffer.extend_from_slice(&padding);
    }
    buffer.freeze()
}

/// Largest TCP response message the server may send. Matches upstream Hysteria
/// (`MaxMessageLength`), which rejects longer messages as a protocol error.
const MAX_TCP_RESPONSE_MESSAGE_LEN: usize = 2048;
/// Largest TCP response padding the server may send. Matches upstream Hysteria
/// (`MaxPaddingLength`).
const MAX_TCP_RESPONSE_PADDING_LEN: u64 = 4096;

/// Read the server's reply to a TCP request.
///
/// Both lengths are server-controlled QUIC varints (up to 2^62 - 1). They are
/// checked against the upstream limits before any allocation or read, so a
/// hostile server cannot make the client allocate (and abort on allocation
/// failure) or drain an unbounded padding stream.
pub(crate) async fn read_tcp_response<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<(bool, String)> {
    let status = reader.read_u8().await? == 0;
    let message_len = read_varint(reader).await?;
    let message_len = usize::try_from(message_len)
        .ok()
        .filter(|len| *len <= MAX_TCP_RESPONSE_MESSAGE_LEN)
        .ok_or_else(|| invalid_response(format!("Hysteria TCP response message length {message_len} exceeds limit")))?;
    let mut message = vec![0u8; message_len];
    reader.read_exact(&mut message).await?;
    let padding_len = read_varint(reader).await?;
    if padding_len > MAX_TCP_RESPONSE_PADDING_LEN {
        return Err(invalid_response(format!("Hysteria TCP response padding length {padding_len} exceeds limit")));
    }
    if padding_len > 0 {
        let skipped = tokio::io::copy(&mut reader.take(padding_len), &mut sink()).await?;
        if skipped != padding_len {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Hysteria TCP response padding is truncated"));
        }
    }

    Ok((status, String::from_utf8_lossy(&message).to_string()))
}

fn invalid_response(message: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Encode a TCP response: status byte, varint message length, message, varint
    /// padding length, padding. The declared lengths can differ from the bytes that
    /// follow, to model a hostile server.
    fn response(status: u8, message_len: u64, message: &[u8], padding_len: u64, padding: &[u8]) -> Vec<u8> {
        let mut buffer = BytesMut::new();
        buffer.extend_from_slice(&[status]);
        put_varint(message_len, &mut buffer);
        buffer.extend_from_slice(message);
        put_varint(padding_len, &mut buffer);
        buffer.extend_from_slice(padding);
        buffer.to_vec()
    }

    #[tokio::test]
    async fn tcp_response_accepts_message_and_padding_at_limits() {
        let message = vec![b'm'; MAX_TCP_RESPONSE_MESSAGE_LEN];
        let padding = vec![0u8; MAX_TCP_RESPONSE_PADDING_LEN as usize];
        let bytes = response(0, message.len() as u64, &message, MAX_TCP_RESPONSE_PADDING_LEN, &padding);

        let (status, parsed) = read_tcp_response(&mut bytes.as_slice()).await.expect("valid response");

        assert!(status);
        assert_eq!(parsed.as_bytes(), message.as_slice());
    }

    #[tokio::test]
    async fn tcp_response_reports_server_failure_message() {
        let bytes = response(1, 7, b"refused", 0, &[]);

        let (status, parsed) = read_tcp_response(&mut bytes.as_slice()).await.expect("valid response");

        assert!(!status);
        assert_eq!(parsed, "refused");
    }

    #[tokio::test]
    async fn tcp_response_rejects_oversized_message_length() {
        let bytes = response(0, MAX_TCP_RESPONSE_MESSAGE_LEN as u64 + 1, b"short", 0, &[]);

        let error = read_tcp_response(&mut bytes.as_slice()).await.expect_err("oversized message must fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    /// The largest QUIC varint (2^62 - 1) must be rejected before any allocation.
    /// Without the bound, this length reached `vec![0u8; len]` and the failed
    /// allocation aborted the whole process (an abort is not catchable).
    #[tokio::test]
    async fn tcp_response_rejects_max_varint_message_length_without_allocating() {
        let bytes = response(0, 0x3FFF_FFFF_FFFF_FFFF, &[], 0, &[]);

        let error = read_tcp_response(&mut bytes.as_slice()).await.expect_err("huge message must fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[tokio::test]
    async fn tcp_response_rejects_oversized_padding_length() {
        let bytes = response(0, 2, b"ok", MAX_TCP_RESPONSE_PADDING_LEN + 1, &[0u8; 16]);

        let error = read_tcp_response(&mut bytes.as_slice()).await.expect_err("oversized padding must fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[tokio::test]
    async fn tcp_response_rejects_truncated_padding() {
        let bytes = response(0, 2, b"ok", 32, &[0u8; 8]);

        let error = read_tcp_response(&mut bytes.as_slice()).await.expect_err("truncated padding must fail");

        assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
    }

    #[tokio::test]
    async fn tcp_response_rejects_max_varint_padding_length() {
        let bytes = response(0, 2, b"ok", 0x3FFF_FFFF_FFFF_FFFF, &[]);

        let error = read_tcp_response(&mut bytes.as_slice()).await.expect_err("huge padding must fail");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }
}
