use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use tokio::net::TcpStream as TokioTcpStream;

use super::super::state::ResolverInner;
use super::health;
use crate::types::{DirectTcpConnector, EncryptedDnsError};

pub(super) async fn connect_direct_tcp_with_hook(
    inner: &ResolverInner,
    connector: Arc<DirectTcpConnector>,
) -> Result<TokioTcpStream, EncryptedDnsError> {
    let mut last_error = None;

    for ip in health::ranked_bootstrap_ips(inner) {
        let address = SocketAddr::new(ip, inner.endpoint.port);
        let started = Instant::now();
        match spawn_blocking_connect(connector.clone(), address, inner.timeout).await {
            Ok(stream) => {
                health::record_bootstrap_outcome(inner, ip, true, started.elapsed());
                return Ok(stream);
            }
            Err(HookConnectError::Connect(err)) => {
                health::record_bootstrap_outcome(inner, ip, false, started.elapsed());
                last_error = Some(err);
            }
            Err(HookConnectError::Adapt(err)) => return Err(EncryptedDnsError::Request(err)),
            Err(HookConnectError::Join(err)) => return Err(EncryptedDnsError::TaskJoin(err)),
        }
    }

    Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
}

enum HookConnectError {
    Adapt(String),
    Connect(String),
    Join(String),
}

async fn spawn_blocking_connect(
    connector: Arc<DirectTcpConnector>,
    address: SocketAddr,
    timeout: std::time::Duration,
) -> Result<TokioTcpStream, HookConnectError> {
    match tokio::task::spawn_blocking(move || connector(address, timeout)).await {
        Ok(Ok(stream)) => {
            let _ = stream.set_nodelay(true);
            stream.set_nonblocking(true).map_err(|err| HookConnectError::Adapt(err.to_string()))?;
            TokioTcpStream::from_std(stream).map_err(|err| HookConnectError::Adapt(err.to_string()))
        }
        Ok(Err(err)) => Err(HookConnectError::Connect(err.to_string())),
        Err(err) => Err(HookConnectError::Join(err.to_string())),
    }
}
