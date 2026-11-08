use etherparse::{NetSlice, UdpHeader, UdpSlice};

pub(super) fn udp_checksum_is_valid(net: &NetSlice<'_>, udp: &UdpSlice<'_>) -> bool {
    if usize::from(udp.length()) < UdpHeader::LEN {
        return false;
    }
    let checksum = udp.checksum();
    let header = udp.to_header();
    match net {
        NetSlice::Ipv4(ip) => {
            checksum == 0
                || header
                    .calc_checksum_ipv4_raw(ip.header().source(), ip.header().destination(), udp.payload())
                    .is_ok_and(|expected| expected == checksum)
        }
        NetSlice::Ipv6(ip) => {
            checksum != 0
                && header
                    .calc_checksum_ipv6_raw(ip.header().source(), ip.header().destination(), udp.payload())
                    .is_ok_and(|expected| expected == checksum)
        }
        NetSlice::Arp(_) => false,
    }
}
