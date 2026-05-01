use std::net::{IpAddr, SocketAddr};

use smoltcp::wire::IpAddress;

pub(crate) fn endpoint_to_socketaddr(ep: smoltcp::wire::IpEndpoint) -> SocketAddr {
    let ip: IpAddr = match ep.addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(v4),
        IpAddress::Ipv6(v6) => IpAddr::V6(v6),
    };
    SocketAddr::new(ip, ep.port)
}
