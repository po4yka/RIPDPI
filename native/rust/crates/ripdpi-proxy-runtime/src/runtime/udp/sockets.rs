use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::os::unix::net::UnixDatagram;

use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;

pub(crate) trait ClientDatagrams {
    fn recv_client(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)>;
    fn send_client(&self, buf: &[u8], to: SocketAddr) -> io::Result<usize>;
}

impl ClientDatagrams for UdpSocket {
    fn recv_client(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)> {
        self.recv_from(buf)
    }

    fn send_client(&self, buf: &[u8], to: SocketAddr) -> io::Result<usize> {
        self.send_to(buf, to)
    }
}

pub(crate) const UNIX_CLIENT_ADDR: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);

impl ClientDatagrams for UnixDatagram {
    fn recv_client(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)> {
        match self.recv(buf)? {
            0 => Err(io::ErrorKind::WouldBlock.into()),
            n => Ok((n, UNIX_CLIENT_ADDR)),
        }
    }

    fn send_client(&self, buf: &[u8], _to: SocketAddr) -> io::Result<usize> {
        self.send(buf)
    }
}

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
///
/// The bind is exclusive (no `SO_REUSEADDR`): on Linux another app's socket that sets
/// it could otherwise bind the same loopback port and receive the client's datagrams.
pub(crate) fn build_udp_relay_sockets(ip: IpAddr) -> io::Result<UdpRelaySockets> {
    let client = UdpSocket::bind(SocketAddr::new(ip, 0))?;
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
