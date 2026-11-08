use std::net::IpAddr;

use etherparse::{Icmpv4Type, Icmpv6Type, PacketBuilder};

mod quote;
pub(crate) use quote::UdpErrorQuote;

#[derive(Clone, Copy)]
pub(crate) enum UdpErrorReason {
    #[cfg(test)]
    PortUnreachable,
    LocalForwardingFailure,
}

impl UdpErrorQuote {
    pub(crate) fn build(&self, source: IpAddr, reason: UdpErrorReason) -> Option<Vec<u8>> {
        if !is_unicast(source) {
            return None;
        }
        let mut output = Vec::new();
        match (source, self.source) {
            (IpAddr::V4(source), IpAddr::V4(destination)) => {
                let code = match reason {
                    #[cfg(test)]
                    UdpErrorReason::PortUnreachable => etherparse::icmpv4::DestUnreachableHeader::Port,
                    UdpErrorReason::LocalForwardingFailure => etherparse::icmpv4::DestUnreachableHeader::Host,
                };
                PacketBuilder::ipv4(source.octets(), destination.octets(), 64)
                    .icmpv4(Icmpv4Type::DestinationUnreachable(code))
                    .write(&mut output, &self.original)
                    .ok()?;
            }
            (IpAddr::V6(source), IpAddr::V6(destination)) => {
                let code = match reason {
                    #[cfg(test)]
                    UdpErrorReason::PortUnreachable => etherparse::icmpv6::DestUnreachableCode::Port,
                    UdpErrorReason::LocalForwardingFailure => etherparse::icmpv6::DestUnreachableCode::Address,
                };
                PacketBuilder::ipv6(source.octets(), destination.octets(), 64)
                    .icmpv6(Icmpv6Type::DestinationUnreachable(code))
                    .write(&mut output, &self.original)
                    .ok()?;
            }
            _ => return None,
        }
        Some(output)
    }
}

fn is_unicast(address: IpAddr) -> bool {
    !address.is_unspecified() && !address.is_multicast() && !matches!(address, IpAddr::V4(ip) if ip.is_broadcast())
}

#[cfg(test)]
pub(crate) fn build_udp_port_unreachable(packet: &[u8]) -> Vec<u8> {
    UdpErrorQuote::capture(packet)
        .and_then(|quote| quote.build(quote.destination(), UdpErrorReason::PortUnreachable))
        .unwrap_or_default()
}

#[cfg(test)]
#[path = "icmp_tests.rs"]
mod tests;
