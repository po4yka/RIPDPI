use std::io;
use std::net::{IpAddr, SocketAddr, UdpSocket};

use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;

pub(crate) struct UdpRelaySockets {
    pub(crate) client: UdpSocket,
}

pub(crate) fn build_udp_relay_sockets(ip: IpAddr, _protect_path: Option<&str>) -> io::Result<UdpRelaySockets> {
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
