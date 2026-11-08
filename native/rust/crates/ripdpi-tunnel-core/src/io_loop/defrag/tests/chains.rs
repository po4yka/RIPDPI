use super::*;

// Scapy 2.6.1 wire vectors; the expected packet is built independently of reassembly.
const PRE_FIRST: &str = "6a55432100280025fd00000000000000000000000000000220010db80000000000000000000000092c0040025858010011000001000000669c4001bb002c44d24142434445464748494a4b4c4d4e4f50";
const PRE_LAST: &str = "6000000000240040fd00000000000000000000000000000220010db80000000000000000000000092c0001040000000011000018000000665152535455565758595a30313233343536373839";
const PRE_COMPLETE: &str = "6a55432100340025fd00000000000000000000000000000220010db800000000000000000000000911004002585801009c4001bb002c44d24142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839";
const POST_FIRST: &str = "6000000000202c40fd00000000000000000000000000000220010db80000000000000000000000093c0000010000006511000104000000009c4001bb002c44d24142434445464748";
const POST_LAST: &str = "6000000000242c40fd00000000000000000000000000000220010db80000000000000000000000093c00001800000065494a4b4c4d4e4f505152535455565758595a30313233343536373839";
const POST_COMPLETE: &str = "6000000000343c40fd00000000000000000000000000000220010db800000000000000000000000911000104000000009c4001bb002c44d24142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839";

fn decode(value: &str) -> Vec<u8> {
    hex::decode(value).expect("fixed Scapy vector")
}

#[test]
fn fragmentable_destination_options_reassemble_in_both_orders() {
    for parts in [[POST_FIRST, POST_LAST], [POST_LAST, POST_FIRST]] {
        let mut defrag = IpDefrag::default();
        let now = Instant::now();
        assert!(matches!(defrag.accept(&decode(parts[0]), now), Reassembly::Held));
        let Reassembly::Complete(packet) = defrag.accept(&decode(parts[1]), now) else {
            panic!("the complete fragmentable extension chain must be restored");
        };
        assert_eq!(packet, decode(POST_COMPLETE));
    }
}

#[test]
fn reassembly_uses_the_first_fragments_base_header_and_options() {
    let mut defrag = IpDefrag::default();
    let now = Instant::now();
    assert!(matches!(defrag.accept(&decode(PRE_LAST), now), Reassembly::Held));
    let Reassembly::Complete(packet) = defrag.accept(&decode(PRE_FIRST), now) else {
        panic!("out-of-order fragments must retain the first fragment headers");
    };
    assert_eq!(packet, decode(PRE_COMPLETE));
}

#[test]
fn first_fragment_must_include_the_udp_header_after_destination_options() {
    let mut first = decode(POST_FIRST);
    first.truncate(56); // Fragment and Destination Options, but no UDP header.
    first[4..6].copy_from_slice(&16u16.to_be_bytes());
    let mut last = decode(POST_LAST)[..48].to_vec();
    last[4..6].copy_from_slice(&52u16.to_be_bytes());
    last[42..44].copy_from_slice(&8u16.to_be_bytes());
    last.extend_from_slice(&decode(POST_COMPLETE)[48..]);
    let now = Instant::now();
    let mut defrag = IpDefrag::default();
    assert!(matches!(defrag.accept(&first, now), Reassembly::Held));
    assert!(matches!(defrag.accept(&last, now), Reassembly::Held));
}

#[test]
fn only_the_offset_zero_fragment_selects_the_next_header() {
    // RFC 8200 section 4.5 permits non-first fragments to carry a different Next Header.
    for next_header in [IpNumber::TCP.0, IpNumber::UDP.0, IpNumber::IPV6_ICMP.0, 255] {
        let first = decode(POST_FIRST);
        let mut last = decode(POST_LAST);
        last[40] = next_header;
        for parts in [[&first, &last], [&last, &first]] {
            let now = Instant::now();
            let mut defrag = IpDefrag::default();
            assert!(matches!(defrag.accept(parts[0], now), Reassembly::Held));
            let Reassembly::Complete(packet) = defrag.accept(parts[1], now) else {
                panic!("only the first fragment selects the fragmentable chain");
            };
            assert_eq!(packet, decode(POST_COMPLETE));
        }
    }
}

// Scapy 2.6.1: complete ICMPv6 Echo header in the first fragment.
const ICMP_FIRST: &str = "6000000000202c40fd00000000000000000000000000000220010db80000000000000000000000073a0000010000007d800073d70001000107070707070707070707070707070707";
const ICMP_LAST: &str = "6000000000382c40fd00000000000000000000000000000220010db80000000000000000000000073a0000180000007d070707070707070707070707070707070707070707070707070707070707070707070707070707070707070707070707";

#[test]
fn known_first_icmp_fragment_uses_the_legacy_path() {
    let mut defrag = IpDefrag::default();
    assert!(matches!(defrag.accept(&decode(ICMP_FIRST), Instant::now()), Reassembly::NotFragment));
}

#[test]
fn non_udp_first_fragment_discards_the_unknown_candidate() {
    let mut defrag = IpDefrag::default();
    let now = Instant::now();
    assert!(matches!(defrag.accept(&decode(ICMP_LAST), now), Reassembly::Held));
    assert!(matches!(defrag.accept(&decode(ICMP_FIRST), now), Reassembly::NotFragment));
    let datagram = udp_datagram_v6(&[7; 64]);
    let first = fragment_v6(125, &datagram, 0, 24);
    let last = fragment_v6(125, &datagram, 24, datagram.len() - 24);
    assert!(matches!(defrag.accept(&first, now), Reassembly::Held));
    assert!(matches!(defrag.accept(&last, now), Reassembly::Complete(_)));
}
