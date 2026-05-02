use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::time::Instant;

use super::super::connection::TcpClientStream;
use super::super::state::ResolverInner;
use super::{health, timeouts};
use crate::types::EncryptedDnsError;

pub(super) async fn connect_direct_tcp_with<S, C, F>(
    inner: &ResolverInner,
    mut connect: C,
) -> Result<S, EncryptedDnsError>
where
    S: TcpClientStream,
    C: FnMut(SocketAddr) -> F,
    F: Future<Output = io::Result<S>>,
{
    let mut last_error = None;
    for ip in health::ranked_bootstrap_ips(inner) {
        let address = SocketAddr::new(ip, inner.endpoint.port);
        let started = Instant::now();
        match timeouts::request_timeout(inner.timeout, connect(address), || format!("connect to {address} timed out"))
            .await
        {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay_if_supported(true);
                health::record_bootstrap_outcome(inner, ip, true, started.elapsed());
                return Ok(stream);
            }
            Ok(Err(err)) => {
                health::record_bootstrap_timeout(inner, ip);
                last_error = Some(err.to_string());
            }
            Err(message) => {
                health::record_bootstrap_timeout(inner, ip);
                last_error = Some(message);
            }
        }
    }
    Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
}
