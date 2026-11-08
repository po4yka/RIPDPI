use super::UdpErrorQuote;
use etherparse::{Icmpv4Type, Icmpv6Type, PacketBuilder};
use ripdpi_socks5_core::udp_feedback::RemoteError;
use std::net::IpAddr;

impl UdpErrorQuote {
    pub(crate) fn build_remote(&self, error: &RemoteError) -> Option<Vec<u8>> {
        // Validate the same narrow numeric contract even for an internal caller.
        ripdpi_socks5_core::udp_feedback::encode_error(&[0; 16], 1, error).ok()?;
        let mut output = Vec::new();
        let rest = error.info.to_be_bytes();
        match (error.source, self.source()) {
            (IpAddr::V4(src), IpAddr::V4(dst)) => {
                PacketBuilder::ipv4(src.octets(), dst.octets(), 64)
                    .icmpv4(Icmpv4Type::Unknown { type_u8: error.icmp_type, code_u8: error.icmp_code, bytes5to8: rest })
                    .write(&mut output, &self.original)
                    .ok()?;
            }
            (IpAddr::V6(src), IpAddr::V6(dst)) => {
                PacketBuilder::ipv6(src.octets(), dst.octets(), 64)
                    .icmpv6(Icmpv6Type::Unknown { type_u8: error.icmp_type, code_u8: error.icmp_code, bytes5to8: rest })
                    .write(&mut output, &self.original)
                    .ok()?;
            }
            _ => return None,
        }
        Some(output)
    }
}
