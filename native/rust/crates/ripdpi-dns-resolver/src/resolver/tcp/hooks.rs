use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use tokio::net::TcpStream as TokioTcpStream;

use super::super::state::ResolverInner;
use super::health;
use super::timeouts;
use crate::types::{BoxedDnsTcpStream, DirectTcpConnection, DirectTcpConnector, EncryptedDnsError};

pub(super) async fn connect_tokio_tcp_with_hook(
    inner: &ResolverInner,
    connector: Arc<DirectTcpConnector>,
) -> Result<TokioTcpStream, EncryptedDnsError> {
    match connect_with_hook(inner, connector).await? {
        DirectTcpConnection::Std(stream) => adapt_std_tcp_stream(stream).map_err(EncryptedDnsError::Request),
        DirectTcpConnection::Async(_) => {
            Err(EncryptedDnsError::Request("async direct TCP connector streams are only supported by DoH".to_string()))
        }
    }
}

pub(super) async fn connect_tokio_tcp_target_with_hook(
    connector: Arc<DirectTcpConnector>,
    target: SocketAddr,
    timeout: std::time::Duration,
) -> std::io::Result<TokioTcpStream> {
    match connect_one(connector, target, timeout).await {
        Ok(DirectTcpConnection::Std(stream)) => adapt_std_tcp_stream(stream).map_err(std::io::Error::other),
        Ok(DirectTcpConnection::Async(_)) => {
            Err(std::io::Error::other("async direct TCP connector streams are only supported by DoH"))
        }
        Err(HookConnectError::Connect(error)) => Err(std::io::Error::other(error)),
    }
}

pub(super) async fn connect_boxed_tcp_with_hook(
    inner: &ResolverInner,
    connector: Arc<DirectTcpConnector>,
) -> Result<BoxedDnsTcpStream, EncryptedDnsError> {
    match connect_with_hook(inner, connector).await? {
        DirectTcpConnection::Std(stream) => adapt_std_tcp_stream(stream)
            .map(|stream| Box::new(stream) as BoxedDnsTcpStream)
            .map_err(EncryptedDnsError::Request),
        DirectTcpConnection::Async(stream) => Ok(stream),
    }
}

async fn connect_with_hook(
    inner: &ResolverInner,
    connector: Arc<DirectTcpConnector>,
) -> Result<DirectTcpConnection, EncryptedDnsError> {
    let mut last_error = None;

    for ip in health::ranked_bootstrap_ips(inner) {
        let address = SocketAddr::new(ip, inner.endpoint.port);
        let started = Instant::now();
        match connect_one(connector.clone(), address, inner.timeout).await {
            Ok(stream) => {
                health::record_bootstrap_outcome(inner, ip, true, started.elapsed());
                return Ok(stream);
            }
            Err(HookConnectError::Connect(err)) => {
                health::record_bootstrap_outcome(inner, ip, false, started.elapsed());
                last_error = Some(err);
            }
        }
    }

    Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
}

enum HookConnectError {
    Connect(String),
}

async fn connect_one(
    connector: Arc<DirectTcpConnector>,
    address: SocketAddr,
    timeout: std::time::Duration,
) -> Result<DirectTcpConnection, HookConnectError> {
    timeouts::request_timeout(timeout, connector(address, timeout), || format!("connect to {address} timed out"))
        .await
        .map_err(HookConnectError::Connect)?
        .map_err(|err| HookConnectError::Connect(err.to_string()))
}

fn adapt_std_tcp_stream(stream: std::net::TcpStream) -> Result<TokioTcpStream, String> {
    let _ = stream.set_nodelay(true);
    stream.set_nonblocking(true).map_err(|err| err.to_string())?;
    TokioTcpStream::from_std(stream).map_err(|err| err.to_string())
}
