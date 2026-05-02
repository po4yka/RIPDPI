#[cfg(test)]
use std::io::{Read, Write};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::types::EncryptedDnsError;

#[cfg(test)]
pub(crate) fn write_length_prefixed_frame(stream: &mut impl Write, payload: &[u8]) -> Result<(), EncryptedDnsError> {
    let length = dns_frame_len(payload)?;
    stream.write_all(&length.to_be_bytes()).map_err(request_error)?;
    stream.write_all(payload).map_err(request_error)?;
    stream.flush().map_err(request_error)
}

#[inline(never)]
pub(crate) async fn write_length_prefixed_frame_async(
    stream: &mut (impl AsyncWrite + Unpin),
    payload: &[u8],
) -> Result<(), EncryptedDnsError> {
    let length = dns_frame_len(payload)?;
    stream.write_all(&length.to_be_bytes()).await.map_err(request_error)?;
    stream.write_all(payload).await.map_err(request_error)?;
    stream.flush().await.map_err(request_error)
}

#[cfg(test)]
pub(crate) fn read_length_prefixed_frame(stream: &mut impl Read) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut length = [0u8; 2];
    stream.read_exact(&mut length).map_err(request_error)?;
    let frame_len = usize::from(u16::from_be_bytes(length));
    let mut payload = vec![0u8; frame_len];
    stream.read_exact(&mut payload).map_err(request_error)?;
    Ok(payload)
}

#[inline(never)]
pub(crate) async fn read_length_prefixed_frame_async(
    stream: &mut (impl AsyncRead + Unpin),
) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut length = [0u8; 2];
    stream.read_exact(&mut length).await.map_err(request_error)?;
    let frame_len = usize::from(u16::from_be_bytes(length));
    let mut payload = vec![0u8; frame_len];
    stream.read_exact(&mut payload).await.map_err(request_error)?;
    Ok(payload)
}

#[inline(never)]
pub(crate) async fn consume_socks5_bind_address_async(
    stream: &mut (impl AsyncRead + Unpin),
    atyp: u8,
) -> Result<(), EncryptedDnsError> {
    match atyp {
        0x01 => consume_fixed_socks5_address(stream, 6).await,
        0x04 => consume_fixed_socks5_address(stream, 18).await,
        0x03 => consume_domain_socks5_address(stream).await,
        value => Err(EncryptedDnsError::Socks5(format!("unsupported bind atyp: {value}"))),
    }
}

fn dns_frame_len(payload: &[u8]) -> Result<u16, EncryptedDnsError> {
    u16::try_from(payload.len())
        .map_err(|_| EncryptedDnsError::Request("DNS payload is too large for TCP framing".to_string()))
}

async fn consume_fixed_socks5_address(
    stream: &mut (impl AsyncRead + Unpin),
    len: usize,
) -> Result<(), EncryptedDnsError> {
    let mut address = vec![0u8; len];
    stream.read_exact(&mut address).await.map_err(socks5_error)?;
    Ok(())
}

async fn consume_domain_socks5_address(stream: &mut (impl AsyncRead + Unpin)) -> Result<(), EncryptedDnsError> {
    let mut len = [0u8; 1];
    stream.read_exact(&mut len).await.map_err(socks5_error)?;
    let mut address = vec![0u8; len[0] as usize + 2];
    stream.read_exact(&mut address).await.map_err(socks5_error)?;
    Ok(())
}

fn request_error(error: std::io::Error) -> EncryptedDnsError {
    EncryptedDnsError::Request(error.to_string())
}

fn socks5_error(error: std::io::Error) -> EncryptedDnsError {
    EncryptedDnsError::Socks5(error.to_string())
}
