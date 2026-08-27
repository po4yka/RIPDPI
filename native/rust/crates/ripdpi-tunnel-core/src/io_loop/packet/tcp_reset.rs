use etherparse::{NetSlice, PacketBuilder};

use super::parse::parse_tcp_slices;

/// Build only a local TUN response; never send denied bytes through a raw hook.
pub(crate) fn build_tcp_reset(packet: &[u8]) -> Option<Vec<u8>> {
    let (net, tcp) = parse_tcp_slices(packet)?;
    if tcp.rst() {
        return None;
    }
    let ip = match net {
        NetSlice::Ipv4(ip) => PacketBuilder::ipv4(ip.header().destination(), ip.header().source(), 64),
        NetSlice::Ipv6(ip) => PacketBuilder::ipv6(ip.header().destination(), ip.header().source(), 64),
        NetSlice::Arp(_) => return None,
    };
    let sequence = if tcp.ack() { tcp.acknowledgment_number() } else { 0 };
    let mut builder = ip.tcp(tcp.destination_port(), tcp.source_port(), sequence, 0).rst();
    if !tcp.ack() {
        let segment_len = u32::try_from(tcp.payload().len()).ok()?;
        let ack = tcp
            .sequence_number()
            .wrapping_add(segment_len)
            .wrapping_add(u32::from(tcp.syn()))
            .wrapping_add(u32::from(tcp.fin()));
        builder = builder.ack(ack);
    }
    // A denied packet produces at most one small, header-only queued response.
    let mut response = Vec::with_capacity(builder.size(0));
    builder.write(&mut response, &[]).ok()?;
    Some(response)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reset_reverses_both_ip_families_and_wraps_syn_fin_payload_ack() {
        for ipv6 in [false, true] {
            let ip = if ipv6 {
                PacketBuilder::ipv6([1; 16], [2; 16], 64)
            } else {
                PacketBuilder::ipv4([10, 0, 0, 2], [203, 0, 113, 1], 64)
            };
            let mut incoming = Vec::new();
            ip.tcp(55_630, 443, u32::MAX, 1024).syn().fin().write(&mut incoming, &[1, 2]).expect("packet");
            let reset = build_tcp_reset(&incoming).expect("reset");
            let (net, tcp) = parse_tcp_slices(&reset).expect("TCP response");
            assert!(tcp.rst() && tcp.ack());
            assert_eq!(tcp.acknowledgment_number(), 3);
            assert_eq!(tcp.sequence_number(), 0);
            assert!(tcp.payload().is_empty());
            let (source, destination) = super::super::parse::tcp_packet_endpoints(&incoming).expect("endpoints");
            assert_eq!(super::super::parse::tcp_packet_endpoints(&reset), Some((destination, source)));
            let header = tcp.to_header();
            let checksum = match net {
                NetSlice::Ipv4(ip) => header.calc_checksum_ipv4(&ip.header().to_header(), &[]),
                NetSlice::Ipv6(ip) => header.calc_checksum_ipv6(&ip.header().to_header(), &[]),
                NetSlice::Arp(_) => unreachable!("IP reset"),
            }
            .expect("checksum");
            assert_eq!(tcp.checksum(), checksum);
            assert!(build_tcp_reset(&reset).is_none(), "never answer a reset with another reset");
        }
    }

    #[test]
    fn reset_for_ack_segment_uses_peer_ack_as_sequence() {
        let mut incoming = Vec::new();
        PacketBuilder::ipv4([10, 0, 0, 2], [203, 0, 113, 1], 64)
            .tcp(55_631, 443, 10, 1024)
            .ack(42)
            .write(&mut incoming, &[1, 2])
            .expect("packet");
        let reset = build_tcp_reset(&incoming).expect("reset");
        let (_, tcp) = parse_tcp_slices(&reset).expect("TCP response");
        assert!(tcp.rst() && !tcp.ack());
        assert_eq!(tcp.sequence_number(), 42);
        assert_eq!(tcp.acknowledgment_number(), 0);
        assert!(build_tcp_reset(&incoming[..10]).is_none(), "truncated IP is discarded");
    }
}
