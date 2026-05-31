use std::io;
use std::net::{IpAddr, SocketAddr, UdpSocket};

use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;

pub(crate) struct UdpRelaySockets {
    pub(crate) client: UdpSocket,
}

/// Bind the **client-facing** UDP relay socket for a SOCKS5 UDP ASSOCIATE.
///
/// `ip` is the proxy's own local interface address (`client.local_addr().ip()`
/// — loopback by default, or the LAN address under allow-LAN). This socket only
/// ever receives datagrams from, and replies to, the local/LAN SOCKS5 client;
/// it is never an internet-egress path. It therefore does NOT take a
/// `protect_path` and is deliberately not run through `VpnService.protect()`:
/// the egress socket that the invariant in
/// `.claude/rules/vpnservice-protect-invariant.md` governs is the upstream
/// socket built by [`build_udp_upstream_socket`], which IS protected.
pub(crate) fn build_udp_relay_sockets(ip: IpAddr) -> io::Result<UdpRelaySockets> {
    let client = udp_platform::bind_udp_socket(SocketAddr::new(ip, 0), None)?;
    client.set_nonblocking(true)?;
    Ok(UdpRelaySockets { client })
}

pub(crate) fn build_udp_upstream_socket(
    target: SocketAddr,
    protect_path: Option<&str>,
    bind_low_port: bool,
) -> io::Result<UdpSocket> {
    udp_platform::build_udp_upstream_socket(target, protect_path, bind_low_port)
}
