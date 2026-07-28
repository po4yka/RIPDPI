use std::io::{self, ErrorKind};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

pub(super) fn advertised_udp_relay_addr(bound: SocketAddr, android_host: &str) -> io::Result<SocketAddr> {
    if !bound.ip().is_unspecified() {
        return Ok(bound);
    }
    let host = android_host.parse::<IpAddr>().map_err(|_| {
        io::Error::new(
            ErrorKind::InvalidInput,
            "android_host must be an IP literal when the SOCKS UDP relay uses a wildcard bind",
        )
    })?;
    Ok(SocketAddr::new(host, bound.port()))
}

pub(super) fn udp_forward_bind_address(destination: SocketAddr) -> SocketAddr {
    // Keep the relay on `config.bind_host`, but let the kernel select a
    // routable source address for each outbound destination family.
    match destination {
        SocketAddr::V4(_) => SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
        SocketAddr::V6(_) => SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn udp_forward_uses_ipv4_wildcard_source_family() {
        let destination = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 2)), 53);

        assert_eq!(udp_forward_bind_address(destination), SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),);
    }

    #[test]
    fn udp_forward_uses_ipv6_wildcard_source_family() {
        let destination = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0x0db8, 0, 0, 0, 0, 0, 2)), 53);

        assert_eq!(udp_forward_bind_address(destination), SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0),);
    }

    #[test]
    fn wildcard_udp_relay_advertises_the_android_reachable_host() {
        let bound = SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 43210);

        assert_eq!(
            advertised_udp_relay_addr(bound, "192.0.2.8").expect("advertised relay"),
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 8)), 43210),
        );
    }

    #[test]
    fn concrete_udp_relay_keeps_its_bound_address() {
        let bound = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 43210);

        assert_eq!(advertised_udp_relay_addr(bound, "not-an-ip").expect("bound relay"), bound);
    }

    #[test]
    fn wildcard_udp_relay_rejects_a_non_literal_android_host() {
        let bound = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 43210);

        let error = advertised_udp_relay_addr(bound, "fixture.invalid").expect_err("invalid relay host");

        assert_eq!(error.kind(), ErrorKind::InvalidInput);
    }
}
