use super::record_framing::is_valid_record_header;
use super::{read_u16, read_u24, ClientHelloOffsetsError, ParsedClientHelloLayout, ParsedExtension};

pub(crate) fn parse_client_hello_layout_in_record(
    buffer: &[u8],
) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
    if buffer.len() < 5 {
        return Err(ClientHelloOffsetsError::TruncatedRecord);
    }
    if !is_valid_record_header(buffer[..5].try_into().expect("checked header len")) {
        return Err(ClientHelloOffsetsError::InvalidRecordHeader);
    }
    let record_len = read_u16(buffer, 3).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
    let payload_end = 5usize.checked_add(record_len).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
    if payload_end != buffer.len() {
        return Err(ClientHelloOffsetsError::TruncatedRecord);
    }
    let mut layout = parse_client_hello_layout_in_handshake_with_base(&buffer[5..payload_end], 5)?;
    layout.raw = buffer.to_vec();
    Ok(layout)
}

pub(crate) fn parse_client_hello_layout_in_handshake(
    buffer: &[u8],
) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
    parse_client_hello_layout_in_handshake_with_base(buffer, 0)
}

fn parse_client_hello_layout_in_handshake_with_base(
    buffer: &[u8],
    base: usize,
) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
    if buffer.len() < 4 {
        return Err(ClientHelloOffsetsError::InvalidHandshakeLength);
    }
    if buffer[0] != 0x01 {
        return Err(ClientHelloOffsetsError::InvalidHandshakeType);
    }
    let handshake_payload_len = read_u24(buffer, 1).ok_or(ClientHelloOffsetsError::InvalidHandshakeLength)?;
    let handshake_end =
        4usize.checked_add(handshake_payload_len).ok_or(ClientHelloOffsetsError::InvalidHandshakeLength)?;
    if handshake_end != buffer.len() {
        return Err(ClientHelloOffsetsError::InvalidHandshakeLength);
    }

    let mut cursor = 4usize;
    cursor = cursor.checked_add(2 + 32).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?;
    if cursor > buffer.len() {
        return Err(ClientHelloOffsetsError::InvalidClientHelloLayout);
    }

    let session_id_len = usize::from(*buffer.get(cursor).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?);
    cursor = cursor.checked_add(1 + session_id_len).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?;
    if cursor > buffer.len() {
        return Err(ClientHelloOffsetsError::InvalidClientHelloLayout);
    }

    let cipher_suites_len = read_u16(buffer, cursor).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?;
    if cipher_suites_len % 2 != 0 {
        return Err(ClientHelloOffsetsError::InvalidClientHelloLayout);
    }
    cursor = cursor.checked_add(2 + cipher_suites_len).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?;
    if cursor > buffer.len() {
        return Err(ClientHelloOffsetsError::InvalidClientHelloLayout);
    }

    let compression_methods_len =
        usize::from(*buffer.get(cursor).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?);
    cursor =
        cursor.checked_add(1 + compression_methods_len).ok_or(ClientHelloOffsetsError::InvalidClientHelloLayout)?;
    if cursor > buffer.len() {
        return Err(ClientHelloOffsetsError::InvalidClientHelloLayout);
    }

    let extensions_len = read_u16(buffer, cursor).ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
    let extensions_start = cursor.checked_add(2).ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
    let extensions_end =
        extensions_start.checked_add(extensions_len).ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
    if extensions_end != buffer.len() {
        return Err(ClientHelloOffsetsError::InvalidExtensionListLength);
    }

    let extensions = parse_extensions(buffer, extensions_start, extensions_end, base)?;
    let payload_span = base..base + buffer.len();
    Ok(ParsedClientHelloLayout {
        raw: buffer.to_vec(),
        payload_spans: vec![payload_span],
        handshake_start: base,
        extensions_start: base + extensions_start,
        extensions_end: base + extensions_end,
        extensions,
    })
}

fn parse_extensions(
    buffer: &[u8],
    extensions_start: usize,
    extensions_end: usize,
    base: usize,
) -> Result<Vec<ParsedExtension>, ClientHelloOffsetsError> {
    let mut extensions = Vec::new();
    let mut ext_cursor = extensions_start;
    while ext_cursor < extensions_end {
        let ext_type = read_u16(buffer, ext_cursor)
            .and_then(|value| u16::try_from(value).ok())
            .ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
        let ext_data_len =
            read_u16(buffer, ext_cursor + 2).ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
        let data_offset = ext_cursor + 4;
        let data_end =
            data_offset.checked_add(ext_data_len).ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
        if data_end > extensions_end {
            return Err(ClientHelloOffsetsError::InvalidExtensionListLength);
        }
        extensions.push(ParsedExtension {
            ext_type,
            type_offset: base + ext_cursor,
            data_offset: base + data_offset,
            data_end: base + data_end,
        });
        ext_cursor = data_end;
    }
    Ok(extensions)
}
