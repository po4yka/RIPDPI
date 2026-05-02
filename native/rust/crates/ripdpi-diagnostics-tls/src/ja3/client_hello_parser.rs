use super::grease::is_grease;

pub(super) struct ClientHelloFields {
    pub(super) version: u16,
    pub(super) cipher_suites: Vec<u16>,
    pub(super) extensions: Vec<u16>,
    pub(super) supported_groups: Vec<u16>,
    pub(super) ec_point_formats: Vec<u16>,
}

/// Read a big-endian u16 from `data` at `pos`, advancing `pos`.
fn read_u16(data: &[u8], pos: &mut usize) -> Option<u16> {
    if *pos + 2 > data.len() {
        return None;
    }
    let value = u16::from_be_bytes([data[*pos], data[*pos + 1]]);
    *pos += 2;
    Some(value)
}

/// Read a single byte from `data` at `pos`, advancing `pos`.
fn read_u8(data: &[u8], pos: &mut usize) -> Option<u8> {
    if *pos >= data.len() {
        return None;
    }
    let value = data[*pos];
    *pos += 1;
    Some(value)
}

/// Skip `n` bytes in `data` at `pos`.
fn skip(data: &[u8], pos: &mut usize, n: usize) -> Option<()> {
    if *pos + n > data.len() {
        return None;
    }
    *pos += n;
    Some(())
}

/// Read a u24 (3-byte big-endian) from `data` at `pos`, advancing `pos`.
fn read_u24(data: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos + 3 > data.len() {
        return None;
    }
    let value = (data[*pos] as u32) << 16 | (data[*pos + 1] as u32) << 8 | (data[*pos + 2] as u32);
    *pos += 3;
    Some(value)
}

/// Parse a TLS ClientHello from the raw bytes written during the handshake.
/// The input should begin with a TLS record header (content type 0x16).
pub(super) fn parse_client_hello(data: &[u8]) -> Option<ClientHelloFields> {
    let mut pos = 0;

    // TLS record header: type(1) + version(2) + length(2)
    let content_type = read_u8(data, &mut pos)?;
    if content_type != 0x16 {
        return None; // not a handshake record
    }
    skip(data, &mut pos, 2)?; // record version (ignored for JA3)
    let _record_length = read_u16(data, &mut pos)?;

    // Handshake header: type(1) + length(3)
    let handshake_type = read_u8(data, &mut pos)?;
    if handshake_type != 0x01 {
        return None; // not ClientHello
    }
    let _handshake_length = read_u24(data, &mut pos)?;

    // ClientHello body
    let version = read_u16(data, &mut pos)?; // client version

    // Random (32 bytes)
    skip(data, &mut pos, 32)?;

    // Session ID
    let session_id_len = read_u8(data, &mut pos)? as usize;
    skip(data, &mut pos, session_id_len)?;

    // Cipher suites
    let cipher_suites_len = read_u16(data, &mut pos)? as usize;
    if pos + cipher_suites_len > data.len() {
        return None;
    }
    let cipher_suites_end = pos + cipher_suites_len;
    let mut cipher_suites = Vec::new();
    while pos < cipher_suites_end {
        let suite = read_u16(data, &mut pos)?;
        if !is_grease(suite) {
            cipher_suites.push(suite);
        }
    }

    // Compression methods
    let compression_len = read_u8(data, &mut pos)? as usize;
    skip(data, &mut pos, compression_len)?;

    // Extensions
    let mut extensions = Vec::new();
    let mut supported_groups = Vec::new();
    let mut ec_point_formats = Vec::new();

    if pos < data.len() {
        let extensions_len = read_u16(data, &mut pos)? as usize;
        if pos + extensions_len > data.len() {
            return None;
        }
        let extensions_end = pos + extensions_len;

        while pos < extensions_end {
            let ext_type = read_u16(data, &mut pos)?;
            let ext_len = read_u16(data, &mut pos)? as usize;
            if pos + ext_len > data.len() {
                return None;
            }
            let ext_data_start = pos;

            if !is_grease(ext_type) {
                extensions.push(ext_type);

                // 0x000a = supported_groups (elliptic_curves)
                if ext_type == 0x000a && ext_len >= 2 {
                    let groups_len = read_u16(data, &mut pos)? as usize;
                    let groups_end = pos + groups_len;
                    while pos < groups_end && pos < ext_data_start + ext_len {
                        let group = read_u16(data, &mut pos)?;
                        if !is_grease(group) {
                            supported_groups.push(group);
                        }
                    }
                }

                // 0x000b = ec_point_formats
                if ext_type == 0x000b && ext_len >= 1 {
                    let formats_len = read_u8(data, &mut pos)? as usize;
                    let formats_end = pos + formats_len;
                    while pos < formats_end && pos < ext_data_start + ext_len {
                        let fmt = read_u8(data, &mut pos)? as u16;
                        ec_point_formats.push(fmt);
                    }
                }
            }

            // Advance past any unread extension data
            pos = ext_data_start + ext_len;
        }
    }

    Some(ClientHelloFields { version, cipher_suites, extensions, supported_groups, ec_point_formats })
}
