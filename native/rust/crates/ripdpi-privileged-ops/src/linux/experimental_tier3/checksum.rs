pub(super) fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let (chunks, remainder) = bytes.as_chunks::<2>();
    for chunk in chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = remainder.first() {
        sum += u32::from(*last) << 8;
    }
    sum
}

pub(super) fn finalize_checksum(mut sum: u32) -> u16 {
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

pub(super) fn icmpv6_checksum(src_ip: [u8; 16], dst_ip: [u8; 16], payload: &[u8]) -> u16 {
    let payload_len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (payload_len >> 16) + (payload_len & 0xffff);
    sum += u32::from(58u16);
    sum += checksum_sum(payload);
    finalize_checksum(sum)
}
