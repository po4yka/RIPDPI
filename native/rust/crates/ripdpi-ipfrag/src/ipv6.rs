use etherparse::{IpFragOffset, IpNumber, Ipv6FlowLabel, Ipv6FragmentHeader, Ipv6Header, ip_number};

use crate::split::IP_FRAGMENT_ALIGNMENT_BYTES;
use crate::{BuildError, IpFragmentPair, Ipv6ExtHeaders};

/// Minimum size of an IPv6 extension header (next_header + hdr_ext_len + 6 pad bytes).
const IPV6_EXT_HDR_MIN_LEN: usize = 8;

/// Serialize a PadN-filled IPv6 extension header.
///
/// Layout: `[next_header, hdr_ext_len, PadN_type=1, PadN_len, zero_padding...]`
/// Total size is always `IPV6_EXT_HDR_MIN_LEN` (8 bytes), which is the minimum
/// extension header size (hdr_ext_len=0 means 8 bytes including the 2 fixed bytes).
fn serialize_pad_extension(next_header: u8, buf: &mut Vec<u8>) {
    buf.push(next_header);
    buf.push(0); // hdr_ext_len = 0 -> total 8 bytes
    // PadN option: type=1, length=4 (fills remaining 6 bytes: type + len + 4 zero bytes)
    buf.push(1); // PadN option type
    buf.push(4); // PadN data length
    buf.extend_from_slice(&[0u8; 4]); // 4 zero padding bytes
}

/// Serialize an IPv6 Routing extension header (type 0, segments_left=0).
///
/// Layout: `[next_header, hdr_ext_len=0, routing_type=0, segments_left=0, reserved...]`
fn serialize_routing_extension(next_header: u8, buf: &mut Vec<u8>) {
    buf.push(next_header);
    buf.push(0); // hdr_ext_len = 0 -> total 8 bytes
    buf.push(0); // routing_type = 0
    buf.push(0); // segments_left = 0
    buf.extend_from_slice(&[0u8; 4]); // reserved
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn build_ipv6_fragment_pair(
    src: [u8; 16],
    dst: [u8; 16],
    ttl: u8,
    identification: u32,
    next_header: IpNumber,
    transport: &[u8],
    split: usize,
    ext: Ipv6ExtHeaders,
) -> Result<IpFragmentPair, BuildError> {
    let first_transport = &transport[..split];
    let second_transport = &transport[split..];

    // Build the unfragmentable extension header chain.
    // Order: HopByHop -> DestOpt -> Routing -> (Fragment Header follows)
    let mut unfrag_ext = Vec::new();
    // Each header's next_header points to the next extension or to the Fragment Header.
    // We build in reverse logical order to know what next_header each should carry.
    // Determine which unfragmentable extensions are present.
    let unfrag_chain: Vec<u8> = {
        let mut chain = Vec::new();
        if ext.hop_by_hop {
            chain.push(0); // IPPROTO_HOPOPTS
        }
        if ext.dest_opt {
            chain.push(60); // IPPROTO_DSTOPTS
        }
        if ext.routing {
            chain.push(43); // IPPROTO_ROUTING
        }
        chain
    };

    // Build headers with correct next_header chaining.
    // The last unfragmentable extension's next_header = IPV6_FRAG (44).
    for (i, &proto) in unfrag_chain.iter().enumerate() {
        let next = if i + 1 < unfrag_chain.len() {
            unfrag_chain[i + 1]
        } else {
            44 // IPV6_FRAG
        };
        match proto {
            43 => serialize_routing_extension(next, &mut unfrag_ext),
            _ => serialize_pad_extension(next, &mut unfrag_ext),
        }
    }

    // The fragmentable destination options header goes after the Fragment Header
    // and before the transport payload. It becomes part of the fragment payload.
    let frag_dest_opt = if ext.dest_opt_fragmentable {
        let mut buf = Vec::with_capacity(IPV6_EXT_HDR_MIN_LEN);
        serialize_pad_extension(next_header.0, &mut buf);
        Some(buf)
    } else {
        None
    };

    // The Fragment Header's next_header field:
    // - If fragmentable dest_opt exists: 60 (DSTOPTS), since dest_opt2 wraps transport
    // - Otherwise: the actual transport protocol (TCP/UDP)
    let frag_hdr_next = if frag_dest_opt.is_some() { IpNumber(60) } else { next_header };

    // IPv6 base header's next_header:
    // - If unfragmentable extensions exist: first extension's protocol number
    // - Otherwise: IPV6_FRAG (44)
    let ipv6_next_header =
        if let Some(&first_proto) = unfrag_chain.first() { IpNumber(first_proto) } else { ip_number::IPV6_FRAG };

    // Calculate payload lengths including all extension headers.
    let frag_dest_opt_len = frag_dest_opt.as_ref().map_or(0, Vec::len);
    let first_payload_len = unfrag_ext.len() + Ipv6FragmentHeader::LEN + frag_dest_opt_len + first_transport.len();
    let second_payload_len = unfrag_ext.len() + Ipv6FragmentHeader::LEN + frag_dest_opt_len + second_transport.len();

    let base_first = Ipv6Header {
        traffic_class: 0,
        flow_label: Ipv6FlowLabel::ZERO,
        payload_length: u16::try_from(first_payload_len).map_err(|_| BuildError::ValueTooLarge)?,
        next_header: ipv6_next_header,
        hop_limit: ttl,
        source: src,
        destination: dst,
    };
    let base_second = Ipv6Header {
        traffic_class: 0,
        flow_label: Ipv6FlowLabel::ZERO,
        payload_length: u16::try_from(second_payload_len).map_err(|_| BuildError::ValueTooLarge)?,
        next_header: ipv6_next_header,
        hop_limit: ttl,
        source: src,
        destination: dst,
    };

    let first_fragment = Ipv6FragmentHeader::new(frag_hdr_next, IpFragOffset::ZERO, true, identification);

    // Next-header forgery on second fragment.
    let second_frag_next = ext.second_frag_next_override.map_or(frag_hdr_next, IpNumber);
    let second_fragment = Ipv6FragmentHeader::new(
        second_frag_next,
        IpFragOffset::try_new(
            u16::try_from((frag_dest_opt_len + split) / IP_FRAGMENT_ALIGNMENT_BYTES)
                .map_err(|_| BuildError::ValueTooLarge)?,
        )
        .map_err(|_| BuildError::ValueTooLarge)?,
        false,
        identification,
    );

    let first_bytes = serialize_ipv6_fragment_ext(
        &base_first,
        &unfrag_ext,
        &first_fragment,
        frag_dest_opt.as_deref(),
        first_transport,
    );
    let second_bytes = serialize_ipv6_fragment_ext(
        &base_second,
        &unfrag_ext,
        &second_fragment,
        frag_dest_opt.as_deref(),
        second_transport,
    );
    Ok(IpFragmentPair { first: first_bytes, second: second_bytes, effective_transport_split: split })
}

fn serialize_ipv6_fragment_ext(
    base: &Ipv6Header,
    unfrag_ext: &[u8],
    fragment: &Ipv6FragmentHeader,
    frag_dest_opt: Option<&[u8]>,
    transport: &[u8],
) -> Vec<u8> {
    let frag_dest_len = frag_dest_opt.map_or(0, <[u8]>::len);
    let mut bytes = Vec::with_capacity(
        Ipv6Header::LEN + unfrag_ext.len() + Ipv6FragmentHeader::LEN + frag_dest_len + transport.len(),
    );
    base.write(&mut bytes).expect("Vec<u8> write must not fail");
    bytes.extend_from_slice(unfrag_ext);
    fragment.write(&mut bytes).expect("Vec<u8> write must not fail");
    if let Some(dest_opt) = frag_dest_opt {
        bytes.extend_from_slice(dest_opt);
    }
    bytes.extend_from_slice(transport);
    bytes
}
