use std::net::IpAddr;
use std::time::Instant;

use ipnet::{IpNet, Ipv4Net, Ipv6Net};
use ripdpi_packets::ip::parse_cidr;
use ripdpi_tunnel_config::TunnelConfig;

use super::packet::{UdpErrorQuote, UdpErrorReason};

pub(super) struct UdpErrorOriginator {
    ipv4: Option<Ipv4Net>,
    ipv6: Option<Ipv6Net>,
    tokens: f64,
    updated: Instant,
}

impl UdpErrorOriginator {
    pub(super) fn new(config: &TunnelConfig) -> Self {
        Self {
            ipv4: config.ipv4.as_deref().and_then(|value| match parse_cidr(value, Some((24, 128))).ok()? {
                IpNet::V4(net) => Some(net),
                IpNet::V6(_) => None,
            }),
            ipv6: config.ipv6.as_deref().and_then(|value| match parse_cidr(value, Some((24, 128))).ok()? {
                IpNet::V6(net) => Some(net),
                IpNet::V4(_) => None,
            }),
            tokens: 10.0,
            updated: Instant::now(),
        }
    }

    pub(super) fn capture(&self, packet: &[u8]) -> Option<Vec<u8>> {
        let quote = UdpErrorQuote::capture(packet)?;
        quote.build(self.local_for(quote.source(), quote.destination())?, UdpErrorReason::LocalForwardingFailure)
    }

    pub(super) fn can_originate(&self, source: IpAddr, destination: IpAddr) -> bool {
        self.local_for(source, destination).is_some()
    }

    fn local_for(&self, source: IpAddr, destination: IpAddr) -> Option<IpAddr> {
        match (source, destination) {
            (IpAddr::V4(src), IpAddr::V4(dst)) => {
                let network = self.ipv4?;
                if network.prefix_len() < 31 && (src == network.broadcast() || dst == network.broadcast()) {
                    return None;
                }
                Some(IpAddr::V4(network.addr()))
            }
            (IpAddr::V6(_), IpAddr::V6(_)) => Some(IpAddr::V6(self.ipv6?.addr())),
            _ => None,
        }
    }

    pub(super) fn allow(&mut self, now: Instant) -> bool {
        self.tokens = (self.tokens + now.saturating_duration_since(self.updated).as_secs_f64() * 10.0).min(10.0);
        self.updated = now;
        if self.tokens < 1.0 {
            return false;
        }
        self.tokens -= 1.0;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn errors_have_a_bounded_burst_and_fractional_refill() {
        let mut originator = UdpErrorOriginator::new(&TunnelConfig::default());
        let start = originator.updated;
        for _ in 0..10 {
            assert!(originator.allow(start));
        }
        assert!(!originator.allow(start));
        assert!(!originator.allow(start + Duration::from_millis(50)));
        assert!(originator.allow(start + Duration::from_millis(100)));
        assert!(!originator.allow(start + Duration::from_millis(100)));
        let later = start + Duration::from_secs(60);
        for _ in 0..10 {
            assert!(originator.allow(later));
        }
        assert!(!originator.allow(later));
    }
}
