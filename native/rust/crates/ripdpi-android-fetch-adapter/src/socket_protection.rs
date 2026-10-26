use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_runtime_platform::protect::{has_protect_callback, protect_socket_via_callback};
use tokio::net::{TcpSocket, TcpStream};
use tokio::time::timeout;

use crate::dns_bootstrap::resolve_connect_targets;

/// Floor for one candidate's connect attempt when more candidates remain.
const MIN_CONNECT_ATTEMPT: Duration = Duration::from_secs(2);

pub(crate) async fn connect_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    let budget = Duration::from_millis(connect_timeout_ms);
    timeout(budget, async {
        let targets = resolve_connect_targets(host, port).await?;
        connect_candidates(host, port, &targets, budget).await
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("connect to {host}:{port} timed out")))?
}

/// Try `targets` in order. Each candidate but the last gets an equal share of
/// `budget` (at least [`MIN_CONNECT_ATTEMPT`]), so a blackholed early address
/// cannot use the whole budget before the others are tried; the last one is
/// bounded only by the caller's outer timeout.
///
/// # Cancel safety
///
/// cancel-safe: dropping it closes the socket being connected.
async fn connect_candidates(host: &str, port: u16, targets: &[SocketAddr], budget: Duration) -> io::Result<TcpStream> {
    let attempt_limit = (budget / u32::try_from(targets.len()).unwrap_or(u32::MAX).max(1)).max(MIN_CONNECT_ATTEMPT);
    let mut last_error = None;
    for (index, target) in targets.iter().copied().enumerate() {
        let socket = tcp_socket_for(target)?;
        protect_socket_if_available(&socket)?;
        let attempt = if index + 1 < targets.len() {
            timeout(attempt_limit, socket.connect(target))
                .await
                .unwrap_or_else(|_| Err(io::Error::new(io::ErrorKind::TimedOut, "connect attempt timed out")))
        } else {
            socket.connect(target).await
        };
        match attempt {
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

pub(crate) fn tcp_socket_for(target: SocketAddr) -> io::Result<TcpSocket> {
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

    // A SYN-dropped first candidate must not use the whole connect budget.
    #[tokio::test]
    async fn blackholed_first_candidate_does_not_starve_the_next_one() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let reachable = listener.local_addr().expect("addr");
        let blackholed: SocketAddr = "192.0.2.1:443".parse().expect("test-net addr");
        let budget = Duration::from_secs(4);

        let stream = timeout(budget, connect_candidates("example.test", 443, &[blackholed, reachable], budget))
            .await
            .expect("second candidate must connect inside the budget")
            .expect("second candidate connects");
        assert_eq!(stream.peer_addr().expect("peer"), reachable);
    }
}
