use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_runtime_platform::protect::{has_protect_callback, protect_socket_via_callback};
use tokio::net::{TcpSocket, TcpStream};
use tokio::time::timeout;

use crate::dns_bootstrap::resolve_connect_targets;

/// Floor for one candidate's connect attempt when more candidates remain.
const MIN_CONNECT_ATTEMPT: Duration = Duration::from_secs(2);

/// # Cancel safety:
/// cancel-safe: dropping it closes active resolution and the connecting socket.
pub(crate) async fn connect_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    connect_transport_filtered(host, port, connect_timeout_ms, |_| true).await
}

/// Make alternate service bindings ineligible for local or special-purpose peers.
///
/// # Cancel safety:
/// cancel-safe: dropping it closes active resolution and the connecting socket.
pub(crate) async fn connect_public_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    connect_transport_filtered(host, port, connect_timeout_ms, public_binding_destination).await
}

/// # Cancel safety:
/// cancel-safe: dropping it closes active resolution and any socket being connected.
async fn connect_transport_filtered(
    host: &str,
    port: u16,
    connect_timeout_ms: u64,
    permitted: impl Fn(IpAddr) -> bool,
) -> io::Result<TcpStream> {
    let budget = Duration::from_millis(connect_timeout_ms);
    timeout(budget, async {
        let mut targets = resolve_connect_targets(host, port).await?;
        targets.retain(|target| permitted(target.ip()));
        if targets.is_empty() {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no permitted ECH binding address"));
        }
        connect_candidates(host, port, &targets, budget).await
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("connect to {host}:{port} timed out")))?
}

fn public_binding_destination(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            let reserved = matches!(a, 0 | 10 | 127 | 224..=255)
                || (a == 100 && (64..=127).contains(&b))
                || (a == 169 && b == 254)
                || (a == 172 && (16..=31).contains(&b))
                || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113);
            !reserved
        }
        IpAddr::V6(ip) => {
            let s = ip.segments();
            // Admit translated public IPv4 peers, but never allow translation to bypass the IPv4 policy.
            let translated = ip.to_ipv4_mapped().or_else(|| match s {
                [0x64, 0xff9b, 0, 0, 0, 0, hi, lo] => Some(((u32::from(hi) << 16) | u32::from(lo)).into()),
                _ => None,
            });
            if let Some(v4) = translated {
                return public_binding_destination(IpAddr::V4(v4));
            }
            (s[0] & 0xe000) == 0x2000
                && !(s[0] == 0x2001 && (s[1] < 0x200 || s[1] == 0xdb8))
                && s[0] != 0x2002
                && !(s[0] == 0x3fff && s[1] < 0x1000)
        }
    }
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

    /// # Cancel safety:
    /// cancel-safe: the future owns its transport attempt and test listener.
    #[tokio::test]
    async fn alternate_tls_binding_cannot_connect_to_a_local_peer() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let address = listener.local_addr().expect("address");
        let error = connect_public_transport("127.0.0.1", address.port(), 1000)
            .await
            .expect_err("must refuse before connecting");
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
    }

    #[test]
    fn alternate_destination_policy_checks_embedded_ipv4() {
        for address in ["::ffff:127.0.0.1", "64:ff9b::a00:1", "100.64.0.1", "fc00::1", "2001:db8::1"] {
            assert!(!public_binding_destination(address.parse().unwrap()), "{address}");
        }
        for address in ["1.1.1.1", "2606:4700::1111", "64:ff9b::101:101"] {
            assert!(public_binding_destination(address.parse().unwrap()), "{address}");
        }
    }

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
