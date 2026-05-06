use bytes::Bytes;
use futures::{SinkExt, StreamExt};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::codec::{Framed, LengthDelimitedCodec};

use crate::types::EncryptedDnsError;

#[inline(never)]
pub(crate) async fn write_length_prefixed_frame_async(
    stream: &mut (impl AsyncWrite + Unpin),
    payload: &[u8],
) -> Result<(), EncryptedDnsError> {
    let _ = dns_frame_len(payload)?;
    let mut framed = Framed::new(stream, dns_length_codec());
    framed.send(Bytes::copy_from_slice(payload)).await.map_err(request_error)
}

#[inline(never)]
pub(crate) async fn read_length_prefixed_frame_async(
    stream: &mut (impl AsyncRead + Unpin),
) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut framed = Framed::new(stream, dns_length_codec());
    let payload = framed
        .next()
        .await
        .ok_or_else(|| EncryptedDnsError::Request("DNS TCP stream closed before frame".to_string()))?
        .map_err(request_error)?;
    Ok(payload.to_vec())
}

fn dns_frame_len(payload: &[u8]) -> Result<u16, EncryptedDnsError> {
    u16::try_from(payload.len())
        .map_err(|_| EncryptedDnsError::Request("DNS payload is too large for TCP framing".to_string()))
}

fn dns_length_codec() -> LengthDelimitedCodec {
    LengthDelimitedCodec::builder().length_field_length(2).big_endian().max_frame_length(u16::MAX as usize).new_codec()
}

fn request_error(error: std::io::Error) -> EncryptedDnsError {
    EncryptedDnsError::Request(error.to_string())
}
