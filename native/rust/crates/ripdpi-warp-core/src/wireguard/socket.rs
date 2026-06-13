use std::net::{Ipv4Addr, SocketAddr};

use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;

use crate::platform::{WarpPlatform, protect_socket_if_configured};

pub(super) fn bind_tunnel_socket(endpoint: SocketAddr, platform: &WarpPlatform) -> anyhow::Result<UdpSocket> {
    let bind_addr = if endpoint.is_ipv4() {
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
    } else {
        "[::]:0".parse().expect("ipv6 bind addr")
    };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    socket.bind(&bind_addr.into())?;
    // Fail-closed: a protect rejection drops `socket` here (closing the fd) and
    // fails tunnel construction rather than letting an unprotected WireGuard
    // socket loop back into the TUN (vpnservice-protect-invariant).
    protect_socket_if_configured(&socket, platform)?;
    socket.set_nonblocking(true)?;
    Ok(UdpSocket::from_std(socket.into())?)
}
