#[derive(Debug, Clone, Copy, Default)]
pub(super) struct DnsHeader {
    pub aa_flag: bool,
    pub tc_flag: bool,
    pub ra_flag: bool,
    pub rcode: u8,
    pub question_count: u16,
    pub answer_count: u16,
    pub authority_count: u16,
    pub additional_count: u16,
}

pub(super) fn parse_header(packet: &[u8]) -> Option<DnsHeader> {
    if packet.len() < 12 {
        return None;
    }

    let flags = u16::from_be_bytes([packet[2], packet[3]]);
    Some(DnsHeader {
        aa_flag: flags & 0x0400 != 0,
        tc_flag: flags & 0x0200 != 0,
        ra_flag: flags & 0x0080 != 0,
        rcode: (flags & 0x000F) as u8,
        question_count: u16::from_be_bytes([packet[4], packet[5]]),
        answer_count: u16::from_be_bytes([packet[6], packet[7]]),
        authority_count: u16::from_be_bytes([packet[8], packet[9]]),
        additional_count: u16::from_be_bytes([packet[10], packet[11]]),
    })
}
