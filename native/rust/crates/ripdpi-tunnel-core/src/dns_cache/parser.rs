use hickory_proto::op::Message;

use super::DnsCacheError;

pub(super) fn primary_question_name(packet: &[u8]) -> Result<String, DnsCacheError> {
    let message = Message::from_vec(packet).map_err(|err| DnsCacheError::DnsParse(err.to_string()))?;
    let query = message.queries.first().ok_or(DnsCacheError::Truncated)?;
    Ok(query.name().to_utf8().trim_end_matches('.').to_string())
}

pub(super) fn dns_question_end(packet: &[u8]) -> Result<usize, DnsCacheError> {
    let question_count = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = skip_dns_name(packet, offset)?;
        offset = offset.checked_add(4).ok_or(DnsCacheError::Truncated)?;
        if offset > packet.len() {
            return Err(DnsCacheError::Truncated);
        }
    }
    Ok(offset)
}

fn skip_dns_name(packet: &[u8], mut offset: usize) -> Result<usize, DnsCacheError> {
    loop {
        let Some(length) = packet.get(offset).copied() else {
            return Err(DnsCacheError::Truncated);
        };
        if length & 0b1100_0000 == 0b1100_0000 {
            if offset + 1 >= packet.len() {
                return Err(DnsCacheError::Truncated);
            }
            return Ok(offset + 2);
        }
        offset += 1;
        if length == 0 {
            return Ok(offset);
        }
        offset += length as usize;
        if offset > packet.len() {
            return Err(DnsCacheError::Truncated);
        }
    }
}
