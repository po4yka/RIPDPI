use std::net::IpAddr;

use smoltcp::wire::{IpProtocol, IpVersion, Ipv4Packet, Ipv6Packet};

use crate::ports::PortProtocol;

pub(super) fn route_protocol(packet: &[u8], source_peer_ip: IpAddr) -> Option<PortProtocol> {
    match IpVersion::of_packet(packet).ok()? {
        IpVersion::Ipv4 => {
            let packet = Ipv4Packet::new_checked(packet).ok()?;
            if packet.dst_addr() != source_peer_ip {
                return None;
            }
            match packet.next_header() {
                IpProtocol::Tcp => Some(PortProtocol::Tcp),
                IpProtocol::Udp => Some(PortProtocol::Udp),
                _ => None,
            }
        }
        IpVersion::Ipv6 => {
            let packet = Ipv6Packet::new_checked(packet).ok()?;
            if packet.dst_addr() != source_peer_ip {
                return None;
            }
            match packet.next_header() {
                IpProtocol::Tcp => Some(PortProtocol::Tcp),
                IpProtocol::Udp => Some(PortProtocol::Udp),
                _ => None,
            }
        }
    }
}
