use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use socket2::Domain;

use crate::util::stable_probe_hash;

const ROUTE_BUCKET_PORT_BASE: u16 = 20_000;
const ROUTE_BUCKET_PORT_SPAN: u16 = 30_000;

pub(crate) fn route_identity(addresses: &[SocketAddr]) -> String {
    addresses.iter().map(SocketAddr::to_string).collect::<Vec<_>>().join("|")
}

pub(crate) fn socket_domain_for(address: SocketAddr) -> Domain {
    if address.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 }
}

pub(crate) fn route_bucket_port(seed: u64, bucket: usize) -> u16 {
    let bucket_seed = stable_probe_hash(seed, &format!("bucket:{bucket}"));
    ROUTE_BUCKET_PORT_BASE + (bucket_seed % u64::from(ROUTE_BUCKET_PORT_SPAN)) as u16
}

pub(crate) fn route_bind_addr(address: SocketAddr, port: u16) -> SocketAddr {
    if address.is_ipv4() {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port)
    } else {
        SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED), port)
    }
}
