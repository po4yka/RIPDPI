use std::net::{IpAddr, SocketAddr};

pub use ripdpi_failure_classifier::*;

pub fn should_track_strategy_target(target: SocketAddr) -> bool {
    !is_tunnel_infrastructure_dns_target(target)
}

fn is_tunnel_infrastructure_dns_target(target: SocketAddr) -> bool {
    if target.port() != 853 {
        return false;
    }
    match target.ip() {
        IpAddr::V4(ipv4) => {
            let [a, b, ..] = ipv4.octets();
            a == 198 && matches!(b, 18 | 19)
        }
        IpAddr::V6(_) => false,
    }
}
