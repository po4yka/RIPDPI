use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use tokio::time::timeout;

use crate::types::EncryptedDnsError;

pub(super) async fn request_timeout<S, F>(
    duration: Duration,
    operation: F,
    timeout_message: impl FnOnce() -> String,
) -> Result<Result<S, io::Error>, String>
where
    F: Future<Output = io::Result<S>>,
{
    match timeout(duration, operation).await {
        Ok(result) => Ok(result),
        Err(_) => Err(timeout_message()),
    }
}

pub(super) async fn socks5_proxy_connect<S, F>(
    duration: Duration,
    proxy_target: SocketAddr,
    operation: F,
) -> Result<S, EncryptedDnsError>
where
    F: Future<Output = io::Result<S>>,
{
    match timeout(duration, operation).await {
        Ok(Ok(stream)) => Ok(stream),
        Ok(Err(err)) => Err(EncryptedDnsError::Socks5(format!("connect to proxy {proxy_target}: {err}"))),
        Err(_) => Err(EncryptedDnsError::Socks5(format!("connect to proxy {proxy_target} timed out"))),
    }
}

pub(super) async fn socks5_handshake<F>(duration: Duration, operation: F) -> Result<(), EncryptedDnsError>
where
    F: Future<Output = Result<(), EncryptedDnsError>>,
{
    match timeout(duration, operation).await {
        Ok(result) => result,
        Err(_) => Err(EncryptedDnsError::Socks5("SOCKS5 handshake timed out".to_string())),
    }
}
