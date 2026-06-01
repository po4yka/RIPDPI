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

/// Minimal DER/X.509 parser to extract the Issuer Common Name (CN) from a
/// leaf certificate without pulling in an external x509 crate.
///
/// X.509 Certificate structure (simplified):
///   SEQUENCE {
///     SEQUENCE (TBSCertificate) {
///       [0] version, INTEGER serial,
///       SEQUENCE signatureAlgorithm,
///       SEQUENCE issuer { SET { SEQUENCE { OID, value } }* },
///       ...
///     }
///   }
///
/// We walk the DER just far enough to reach the issuer field, then scan its
/// RDN SEQUENCEs for OID 2.5.4.3 (id-at-commonName).
fn parse_issuer_cn(der: &[u8]) -> Option<String> {
    // OID 2.5.4.3 (id-at-commonName) encoded in DER
    const OID_CN: &[u8] = &[0x55, 0x04, 0x03];

    let (_, inner) = read_der_sequence(der)?;
    let (_, tbs) = read_der_sequence(inner)?;

    // TBSCertificate fields: [0] version (optional), serialNumber,
    // signatureAlgorithm, issuer, ...
    let mut pos = tbs;

    // Skip optional explicit [0] version tag
    if pos.first().copied() == Some(0xA0) {
        let (rest, _) = read_der_element(pos)?;
        pos = rest;
    }

    // Skip serialNumber (INTEGER)
    let (rest, _) = read_der_element(pos)?;
    pos = rest;

    // Skip signatureAlgorithm (SEQUENCE)
    let (rest, _) = read_der_element(pos)?;
    pos = rest;

    // issuer (SEQUENCE of SETs of SEQUENCEs)
    let (_rest, issuer_bytes) = read_der_sequence(pos)?;

    // Walk each RDN SET looking for OID_CN
    let mut rdn_pos = issuer_bytes;
    while !rdn_pos.is_empty() {
        let (next, set_content) = read_der_element(rdn_pos)?;
        // Each SET contains one or more SEQUENCE { OID, value }
        let mut attr_pos = set_content;
        while !attr_pos.is_empty() {
            let (next_attr, seq_content) = read_der_sequence(attr_pos)?;
            // First element is the OID
            if let Some((value_bytes, oid_bytes)) = read_der_element(seq_content)
                && oid_bytes.len() >= OID_CN.len()
                && oid_bytes.ends_with(OID_CN)
            {
                // Second element is the value (UTF8String, PrintableString, etc.)
                if let Some((_rest, cn_bytes)) = read_der_element(value_bytes) {
                    return String::from_utf8(cn_bytes.to_vec()).ok();
                }
            }
            attr_pos = next_attr;
        }
        rdn_pos = next;
    }

    None
}

/// Read one DER TLV element. Returns (remaining_bytes, content_bytes).
fn read_der_element(data: &[u8]) -> Option<(&[u8], &[u8])> {
    if data.is_empty() {
        return None;
    }
    let _tag = data[0];
    let (len, header_size) = read_der_length(&data[1..])?;
    let total_header = 1 + header_size;
    let end = total_header + len;
    if end > data.len() {
        return None;
    }
    Some((&data[end..], &data[total_header..end]))
}

/// Read one DER SEQUENCE, returning (remaining, inner_content).
fn read_der_sequence(data: &[u8]) -> Option<(&[u8], &[u8])> {
    if data.is_empty() || data[0] != 0x30 {
        return None;
    }
    read_der_element(data)
}

/// Decode a DER length field. Returns (length_value, bytes_consumed).
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
