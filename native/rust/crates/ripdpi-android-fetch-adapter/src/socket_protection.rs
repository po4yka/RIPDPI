use std::io;
use std::net::{SocketAddr, TcpStream as StdTcpStream};
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsConnectHooks;
use ripdpi_runtime_platform::protect::{has_protect_callback, protect_socket_via_callback};
use tokio::net::{TcpSocket, TcpStream};
use tokio::time::timeout;

use crate::dns_bootstrap::resolve_connect_targets;

pub(crate) async fn connect_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    timeout(Duration::from_millis(connect_timeout_ms), connect_transport_inner(host, port))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("connect to {host}:{port} timed out")))?
}

pub(crate) fn owned_fetch_dns_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_direct_tcp_connector(connect_dns_bootstrap)
}

/// Protected TCP connect to a DoH bootstrap address.
///
/// # Cancel safety
///
/// cancel-safe: dropping it closes the unconnected socket; no shared state
/// spans the `.await`.
async fn connect_dns_bootstrap(target: SocketAddr, connect_timeout: Duration) -> io::Result<StdTcpStream> {
    let socket = tcp_socket_for(target)?;
    if has_protect_callback() {
        protect_socket_via_callback(socket.as_raw_fd())
            .map_err(|error| io::Error::new(error.kind(), format!("protect owned fetch DNS socket: {error}")))?;
    }
    // Non-blocking connect: a blocking one parks the runtime worker, so neither
    // this bound nor the resolver's outer timeout could fire.
    let stream = timeout(connect_timeout, socket.connect(target))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("connect to {target} timed out")))??;
    stream.set_nodelay(true)?;
    stream.into_std()
}

async fn connect_transport_inner(host: &str, port: u16) -> io::Result<TcpStream> {
    let targets = resolve_connect_targets(host, port).await?;
    let mut last_error = None;
    for target in targets {
        let socket = tcp_socket_for(target)?;
        protect_socket_if_available(&socket)?;
        match socket.connect(target).await {
            Ok(stream) => return Ok(stream),
            Err(error) => {
                last_error = Some(io::Error::new(error.kind(), format!("connect to {target}: {error}")));
            }
        }
    }
    Err(last_error.unwrap_or_else(|| {
        io::Error::new(io::ErrorKind::AddrNotAvailable, format!("connect to {host}:{port}: no usable addresses"))
    }))
}

fn tcp_socket_for(target: SocketAddr) -> io::Result<TcpSocket> {
    match target {
        SocketAddr::V4(_) => TcpSocket::new_v4(),
        SocketAddr::V6(_) => TcpSocket::new_v6(),
    }
}

fn protect_socket_if_available(socket: &TcpSocket) -> io::Result<()> {
    if has_protect_callback() {
        protect_socket_via_callback(socket.as_raw_fd())
            .map_err(|error| io::Error::new(error.kind(), format!("protect native TLS fetch socket: {error}")))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    // The bootstrap connect must yield to the runtime: on the per-call
    // current-thread runtime another task (the outer timeout) keeps running
    // while a SYN to an unanswered address is pending.
    #[tokio::test]
    async fn dns_bootstrap_connect_does_not_block_the_runtime_worker() {
        let blackholed: SocketAddr = "192.0.2.1:443".parse().expect("test-net addr");
        let connect = tokio::spawn(connect_dns_bootstrap(blackholed, Duration::from_millis(600)));
        let started = std::time::Instant::now();
        tokio::time::sleep(Duration::from_millis(20)).await;
        let ticked = started.elapsed();

        let _ = connect.await.expect("connect task");
        assert!(ticked < Duration::from_millis(300), "runtime worker was blocked by connect for {ticked:?}");
    }
}
