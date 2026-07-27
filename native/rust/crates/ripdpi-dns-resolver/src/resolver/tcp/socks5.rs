use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::time::Instant;

use ripdpi_socks5_core::client::{Config as Socks5Config, Socks5Stream};
use ripdpi_socks5_core::util::target_addr::TargetAddr as Socks5TargetAddr;
use ripdpi_socks5_core::{AuthenticationMethod, Socks5Command};

use super::super::connection::TcpClientStream;
use super::super::state::ResolverInner;
use super::{health, timeouts};
use crate::types::{EncryptedDnsError, EncryptedDnsTransport};

/// cancel-safe: cancellation drops the in-flight proxy stream before any shared
/// bootstrap health outcome is recorded.
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
    let bootstrap_ips = health::ranked_bootstrap_ips(inner);
    if bootstrap_ips.is_empty() {
        let target = Socks5TargetAddr::Domain(inner.endpoint.host.clone(), inner.endpoint.port);
        return connect_socks5_target(inner, proxy_target, target, &mut connect).await;
    }

    let mut last_error = None;
    for ip in bootstrap_ips {
        let started = Instant::now();
        let target = Socks5TargetAddr::Ip(SocketAddr::new(ip, inner.endpoint.port));
        match connect_socks5_target(inner, proxy_target, target, &mut connect).await {
            Ok(stream) => {
                health::record_bootstrap_outcome(inner, ip, true, started.elapsed());
                return Ok(stream);
            }
            Err(error) => {
                health::record_bootstrap_outcome(inner, ip, false, started.elapsed());
                last_error = Some(error);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| EncryptedDnsError::Socks5("no bootstrap addresses".to_string())))
}

/// cancel-safe: cancellation drops the partially negotiated SOCKS stream; no
/// shared resolver state is mutated by this function.
async fn connect_socks5_target<S, C, F>(
    inner: &ResolverInner,
    proxy_target: SocketAddr,
    target: Socks5TargetAddr,
    connect: &mut C,
) -> Result<S, EncryptedDnsError>
where
    S: TcpClientStream,
    C: FnMut(SocketAddr) -> F,
    F: Future<Output = io::Result<S>>,
{
    let proxy_stream = timeouts::socks5_proxy_connect(inner.timeout, proxy_target, connect(proxy_target)).await?;
    let _ = proxy_stream.set_nodelay_if_supported(true);
    let auth = match &inner.transport {
        EncryptedDnsTransport::Direct => None,
        EncryptedDnsTransport::Socks5 { credentials, .. } => {
            credentials.as_ref().map(|credentials| AuthenticationMethod::Password {
                username: credentials.username.clone(),
                password: credentials.password.clone(),
            })
        }
    };
    let mut socks_stream = Socks5Stream::use_stream(proxy_stream, auth, Socks5Config::default())
        .await
        .map_err(|err| socks5_auth_error(proxy_target, err))?;
    timeouts::socks5_handshake(inner.timeout, async {
        socks_stream
            .request(Socks5Command::TCPConnect, target)
            .await
            .map(|_| ())
            .map_err(|err| EncryptedDnsError::Socks5(format!("connect via SOCKS5 proxy {proxy_target}: {err}")))
    })
    .await?;
    Ok(socks_stream.get_socket())
}

fn socks5_auth_error(proxy_target: SocketAddr, error: ripdpi_socks5_core::SocksError) -> EncryptedDnsError {
    let message = error.to_string();
    if message.contains("Can't get chosen auth method") {
        EncryptedDnsError::Socks5("SOCKS5 handshake timed out".to_string())
    } else {
        EncryptedDnsError::Socks5(format!("negotiate auth with {proxy_target}: {message}"))
    }
}
