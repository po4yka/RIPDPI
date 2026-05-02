use std::future::Future;
use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::super::connection::TcpClientStream;
use super::super::state::ResolverInner;
use super::timeouts;
use crate::transport::consume_socks5_bind_address_async;
use crate::types::EncryptedDnsError;

pub(super) async fn connect_socks5_tcp_with<S, C, F>(
    inner: &ResolverInner,
    proxy_target: SocketAddr,
    mut connect: C,
) -> Result<S, EncryptedDnsError>
where
    S: TcpClientStream,
    C: FnMut(SocketAddr) -> F,
    F: Future<Output = io::Result<S>>,
{
    let mut proxy_stream = timeouts::socks5_proxy_connect(inner.timeout, proxy_target, connect(proxy_target)).await?;
    let _ = proxy_stream.set_nodelay_if_supported(true);

    let host_bytes = inner.endpoint.host.as_bytes();
    if host_bytes.len() > u8::MAX as usize {
        return Err(EncryptedDnsError::Socks5("resolver host is too long".to_string()));
    }

    timeouts::socks5_handshake(
        inner.timeout,
        negotiate_socks5(&mut proxy_stream, proxy_target, host_bytes, inner.endpoint.port),
    )
    .await?;
    Ok(proxy_stream)
}

async fn negotiate_socks5<S>(
    proxy_stream: &mut S,
    proxy_target: SocketAddr,
    host_bytes: &[u8],
    resolver_port: u16,
) -> Result<(), EncryptedDnsError>
where
    S: TcpClientStream,
{
    proxy_stream
        .write_all(&[0x05, 0x01, 0x00])
        .await
        .map_err(|err| EncryptedDnsError::Socks5(format!("write auth greeting to {proxy_target}: {err}")))?;
    let mut auth_reply = [0u8; 2];
    proxy_stream
        .read_exact(&mut auth_reply)
        .await
        .map_err(|err| EncryptedDnsError::Socks5(format!("read auth reply from {proxy_target}: {err}")))?;
    if auth_reply != [0x05, 0x00] {
        return Err(EncryptedDnsError::Socks5(format!("unexpected auth reply: {auth_reply:?}")));
    }

    let mut request = Vec::with_capacity(host_bytes.len() + 7);
    request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
    request.extend_from_slice(host_bytes);
    request.extend_from_slice(&resolver_port.to_be_bytes());
    proxy_stream
        .write_all(&request)
        .await
        .map_err(|err| EncryptedDnsError::Socks5(format!("write connect request to {proxy_target}: {err}")))?;

    let mut header = [0u8; 4];
    proxy_stream
        .read_exact(&mut header)
        .await
        .map_err(|err| EncryptedDnsError::Socks5(format!("read connect reply from {proxy_target}: {err}")))?;
    if header[1] != 0x00 {
        return Err(EncryptedDnsError::Socks5(format!("connect reply {:x}", header[1])));
    }
    consume_socks5_bind_address_async(proxy_stream, header[3]).await
}
