use rustls::ClientConnection;

pub(super) fn extract_cert_info(conn: &ClientConnection) -> (Option<usize>, Option<String>) {
    match conn.peer_certificates() {
        Some(certs) if !certs.is_empty() => {
            let chain_length = Some(certs.len());
            let issuer = parse_issuer_cn(certs[0].as_ref());
            (chain_length, issuer)
        }
        _ => (None, None),
    }
}

fn parse_issuer_cn(der: &[u8]) -> Option<String> {
    const OID_CN: &[u8] = &[0x55, 0x04, 0x03];

    let (_, inner) = read_der_sequence(der)?;
    let (_, tbs) = read_der_sequence(inner)?;

    let mut pos = tbs;

    if pos.first().copied() == Some(0xA0) {
        let (rest, _) = read_der_element(pos)?;
        pos = rest;
    }

    let (rest, _) = read_der_element(pos)?;
    pos = rest;

    let (rest, _) = read_der_element(pos)?;
    pos = rest;

    let (_rest, issuer_bytes) = read_der_sequence(pos)?;

    let mut rdn_pos = issuer_bytes;
    while !rdn_pos.is_empty() {
        let (next, set_content) = read_der_element(rdn_pos)?;
        let mut attr_pos = set_content;
        while !attr_pos.is_empty() {
            let (next_attr, seq_content) = read_der_sequence(attr_pos)?;
            if let Some((value_bytes, oid_bytes)) = read_der_element(seq_content)
                && oid_bytes == OID_CN
                && let Some((_rest, cn_bytes)) = read_der_element(value_bytes)
            {
                return String::from_utf8(cn_bytes.to_vec()).ok();
            }
            attr_pos = next_attr;
        }
        rdn_pos = next;
    }

    None
}

fn read_der_element(data: &[u8]) -> Option<(&[u8], &[u8])> {
    if data.is_empty() {
        return None;
    }
    let _tag = data[0];
    let (len, header_size) = read_der_length(&data[1..])?;
    let total_header = 1 + header_size;
    let end = total_header.checked_add(len)?;
    if end > data.len() {
        return None;
    }
    Some((&data[end..], &data[total_header..end]))
}

fn read_der_sequence(data: &[u8]) -> Option<(&[u8], &[u8])> {
    if data.is_empty() || data[0] != 0x30 {
        return None;
    }
    read_der_element(data)
}

fn read_der_length(data: &[u8]) -> Option<(usize, usize)> {
    if data.is_empty() {
        return None;
    }
    let first = data[0] as usize;
    if first < 0x80 {
        Some((first, 1))
    } else {
        let num_bytes = first & 0x7F;
        if num_bytes == 0 || num_bytes > 4 || data.len() < 1 + num_bytes {
            return None;
        }
        let mut length = 0usize;
        for i in 0..num_bytes {
            length = (length << 8) | (data[1 + i] as usize);
        }
        Some((length, 1 + num_bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tlv(tag: u8, content: &[u8]) -> Vec<u8> {
        assert!(content.len() < 0x80, "test helper encodes short lengths only");
        let mut out = vec![tag, content.len() as u8];
        out.extend_from_slice(content);
        out
    }

    fn attribute(oid: &[u8], value: &str) -> Vec<u8> {
        tlv(0x31, &tlv(0x30, &[tlv(0x06, oid), tlv(0x0c, value.as_bytes())].concat()))
    }

    fn certificate_with_issuer(issuer_rdns: &[Vec<u8>]) -> Vec<u8> {
        let tbs = [
            tlv(0xa0, &tlv(0x02, &[0x02])),
            tlv(0x02, &[0x01]),
            tlv(0x30, &tlv(0x06, &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02])),
            tlv(0x30, &issuer_rdns.concat()),
        ]
        .concat();
        tlv(0x30, &tlv(0x30, &tbs))
    }

    #[test]
    fn issuer_cn_needs_the_exact_common_name_oid() {
        let der = certificate_with_issuer(&[
            attribute(&[0x2b, 0x55, 0x04, 0x03], "Not a CN"),
            attribute(&[0x55, 0x04, 0x03], "Real CA"),
        ]);

        assert_eq!(parse_issuer_cn(&der).as_deref(), Some("Real CA"));
    }

    #[test]
    fn maximal_der_length_is_rejected_without_panicking() {
        let der = [0x30, 0x84, 0xff, 0xff, 0xff, 0xff, 0x30, 0x00];

        assert_eq!(read_der_element(&der), None);
        assert_eq!(parse_issuer_cn(&der), None);
    }
}
