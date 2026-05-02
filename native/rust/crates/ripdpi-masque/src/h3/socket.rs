use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::config::MasqueConfig;

pub(super) fn build_client_udp_socket(ipv6: bool, bind_low_port: bool) -> io::Result<std::net::UdpSocket> {
    let bind_addr =
        if ipv6 { SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)) } else { SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)) };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    if ipv6 {
        let _ = socket.set_only_v6(false);
    }
    if bind_low_port {
        try_bind_low_port(&socket, bind_addr.ip())?;
    } else {
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    Ok(socket.into())
}

fn try_bind_low_port(socket: &Socket, bind_ip: IpAddr) -> io::Result<()> {
    for port in [2048u16, 2053, 2080, 2443, 3000, 3074, 4096] {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

pub(super) fn maybe_rebind_quic_endpoint(
    config: &MasqueConfig,
    endpoint: &quinn::Endpoint,
    proxy_addr: SocketAddr,
) -> io::Result<()> {
    if !config.quic_migrate_after_handshake {
        return Ok(());
    }
    let replacement = build_client_udp_socket(proxy_addr.is_ipv6(), config.quic_bind_low_port)?;
    endpoint.rebind(replacement)
}
