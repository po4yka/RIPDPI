use etherparse::{IpFragOffset, IpNumber, Ipv4Header};

use crate::split::IP_FRAGMENT_ALIGNMENT_BYTES;
use crate::{BuildError, IpFragmentPair};

pub(crate) fn build_ipv4_fragment_pair(
    src: [u8; 4],
    dst: [u8; 4],
    ttl: u8,
    identification: u16,
    protocol: IpNumber,
    transport: &[u8],
    split: usize,
) -> Result<IpFragmentPair, BuildError> {
    let first = &transport[..split];
    let second = &transport[split..];

    let mut first_header =
        Ipv4Header::new(u16::try_from(first.len()).map_err(|_| BuildError::ValueTooLarge)?, ttl, protocol, src, dst)
            .map_err(|_| BuildError::ValueTooLarge)?;
    first_header.identification = identification;
    first_header.dont_fragment = false;
    first_header.more_fragments = true;
    first_header.header_checksum = first_header.calc_header_checksum();

    let mut second_header =
        Ipv4Header::new(u16::try_from(second.len()).map_err(|_| BuildError::ValueTooLarge)?, ttl, protocol, src, dst)
            .map_err(|_| BuildError::ValueTooLarge)?;
    second_header.identification = identification;
    second_header.dont_fragment = false;
    second_header.more_fragments = false;
    second_header.fragment_offset = IpFragOffset::try_new(
        u16::try_from(split / IP_FRAGMENT_ALIGNMENT_BYTES).map_err(|_| BuildError::ValueTooLarge)?,
    )
    .map_err(|_| BuildError::ValueTooLarge)?;
    second_header.header_checksum = second_header.calc_header_checksum();

    let first_bytes = serialize_ipv4_fragment(&first_header, first);
    let second_bytes = serialize_ipv4_fragment(&second_header, second);
    Ok(IpFragmentPair { first: first_bytes, second: second_bytes, effective_transport_split: split })
}

pub(crate) fn serialize_ipv4_fragment(header: &Ipv4Header, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + payload.len());
    header.write(&mut bytes).expect("Vec<u8> write must not fail");
    bytes.extend_from_slice(payload);
    bytes
}
