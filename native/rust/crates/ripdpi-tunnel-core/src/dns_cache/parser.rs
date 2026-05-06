use hickory_proto::op::Message;
use hickory_proto::rr::Name;
use hickory_proto::serialize::binary::{BinDecodable, BinDecoder};

use super::DnsCacheError;

pub(super) fn primary_question_name(packet: &[u8]) -> Result<String, DnsCacheError> {
    let message = Message::from_vec(packet).map_err(|err| DnsCacheError::DnsParse(err.to_string()))?;
    let query = message.queries.first().ok_or(DnsCacheError::Truncated)?;
    Ok(query.name().to_utf8().trim_end_matches('.').to_string())
}

pub(super) fn dns_question_end(packet: &[u8]) -> Result<usize, DnsCacheError> {
    let question_count = packet
        .get(4..6)
        .map(|bytes| u16::from_be_bytes([bytes[0], bytes[1]]) as usize)
        .ok_or(DnsCacheError::Truncated)?;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = read_dns_name_end(packet, offset)?;
        offset = offset.checked_add(4).ok_or(DnsCacheError::Truncated)?;
        if offset > packet.len() {
            return Err(DnsCacheError::Truncated);
        }
    }
    Ok(offset)
}

fn read_dns_name_end(packet: &[u8], offset: usize) -> Result<usize, DnsCacheError> {
    if offset > packet.len() {
        return Err(DnsCacheError::Truncated);
    }
    let decoder_offset = u16::try_from(offset).map_err(|_| DnsCacheError::Truncated)?;
    let mut decoder = BinDecoder::new(packet).clone(decoder_offset);
    Name::read(&mut decoder).map_err(|err| DnsCacheError::DnsParse(err.to_string()))?;
    Ok(decoder.index())
}

#[cfg(test)]
mod tests {
    use super::dns_question_end;

    #[test]
    fn dns_question_end_handles_compressed_question_name() {
        let mut packet = vec![
            0x12, 0x34, 0x01, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x', b'a', b'm', b'p',
            b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
        ];
        packet.extend_from_slice(&[0xC0, 0x0C, 0x00, 0x1C, 0x00, 0x01]);

        assert_eq!(dns_question_end(&packet).expect("compressed name parses"), packet.len());
    }
}
