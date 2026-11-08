use std::net::{Ipv4Addr, SocketAddr};

use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;

use crate::platform::{WarpPlatform, protect_socket_if_configured};

/// Requested tunnel socket buffer size (wireguard-go `socketBufferSize`).
const TUNNEL_SOCKET_BUFFER: usize = 7 << 20;

pub(super) fn bind_tunnel_socket(endpoint: SocketAddr, platform: &WarpPlatform) -> anyhow::Result<UdpSocket> {
    let bind_addr = if endpoint.is_ipv4() {
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
    } else {
        "[::]:0".parse().expect("ipv6 bind addr")
    };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    // A bulk transfer arrives in bursts. The kernel default (229376 bytes on Android 15)
    // overflows before the receive task drains the burst, and the lost run of segments
    // stalls TCP inside the tunnel. wireguard-go asks for the same size; the
    // kernel caps it at net.core.rmem_max/wmem_max, so a refusal keeps the default.
    // The effective sizes are logged so a capped buffer on a real device is visible.
    // Linux (and Android) doubles the requested value for bookkeeping and getsockopt
    // reports the doubled size, so the log shows the reported value halved there:
    // a 262144-byte rmem_max cap logs as recv=262144, not 524288.
    let recv_request = socket.set_recv_buffer_size(TUNNEL_SOCKET_BUFFER);
    let send_request = socket.set_send_buffer_size(TUNNEL_SOCKET_BUFFER);
    let usable =
        |reported: usize| if cfg!(any(target_os = "linux", target_os = "android")) { reported / 2 } else { reported };
    tracing::debug!(
        requested = TUNNEL_SOCKET_BUFFER,
        recv = ?socket.recv_buffer_size().map(usable),
        send = ?socket.send_buffer_size().map(usable),
        recv_error = ?recv_request.err(),
        send_error = ?send_request.err(),
        "WARP tunnel socket buffers"
    );
    // Fail-closed: a protect rejection drops `socket` here (closing the fd) and
    // fails tunnel construction rather than letting an unprotected WireGuard
    // socket loop back into the TUN (vpnservice-protect-invariant).
    protect_socket_if_configured(&socket, platform)?;
    socket.bind(&bind_addr.into())?;
    socket.set_nonblocking(true)?;
    Ok(UdpSocket::from_std(socket.into())?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;

    /// # Cancel safety:
    /// cancel-safe: the current-thread runtime owns each socket until the assertion ends.
    #[tokio::test(flavor = "current_thread")]
    async fn carrier_socket_is_protected_before_bind() {
        let platform = WarpPlatform::new().with_socket_protector(|fd| {
            let local =
                nix::sys::socket::getsockname::<nix::sys::socket::SockaddrStorage>(fd).map_err(io::Error::from)?;
            let port = local
                .as_sockaddr_in()
                .map(nix::sys::socket::SockaddrIn::port)
                .or_else(|| local.as_sockaddr_in6().map(nix::sys::socket::SockaddrIn6::port))
                .expect("IP socket");
            if port != 0 {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "protect received an already bound socket"));
            }
            Ok(())
        });
        for endpoint in ["203.0.113.7:51820", "[2001:db8::7]:51820"] {
            let socket =
                bind_tunnel_socket(endpoint.parse().expect("endpoint"), &platform).expect("protect before bind");
            assert_ne!(socket.local_addr().expect("bound address").port(), 0);
        }
    }

    #[tokio::test]
    async fn tunnel_socket_asks_for_buffers_beyond_the_kernel_default() {
        let plain = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP)).expect("plain UDP socket");
        let endpoint = SocketAddr::from((Ipv4Addr::LOCALHOST, 51820));

        let tunnel = Socket::from(
            bind_tunnel_socket(endpoint, &WarpPlatform::default()).expect("tunnel socket").into_std().expect("std"),
        );

        // A bulk download arrives in bursts; the default receive buffer drops them (Udp RcvbufErrors).
        assert!(tunnel.recv_buffer_size().unwrap() > plain.recv_buffer_size().unwrap());
        assert!(tunnel.send_buffer_size().unwrap() > plain.send_buffer_size().unwrap());
    }
}
