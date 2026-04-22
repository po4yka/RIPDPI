use std::ops::Range;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientHelloOffsets {
    pub handshake_start: usize,
    pub extensions_start: usize,
    pub extensions_end: usize,
    pub server_name_extension_start: Option<usize>,
    pub sni_hostname: Option<Range<usize>>,
    pub alpn_extension_start: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientHelloOffsetsError {
    InvalidRecordHeader,
    TruncatedRecord,
    InvalidHandshakeType,
    InvalidHandshakeLength,
    InvalidClientHelloLayout,
    InvalidExtensionListLength,
    InvalidServerNameExtension,
    InvalidAlpnExtension,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ParsedExtension {
    ext_type: u16,
    type_offset: usize,
    data_offset: usize,
    data_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ParsedClientHelloLayout {
    raw: Vec<u8>,
    payload_spans: Vec<Range<usize>>,
    handshake_start: usize,
    extensions_start: usize,
    extensions_end: usize,
    extensions: Vec<ParsedExtension>,
}

pub fn parse_client_hello_offsets(buffer: &[u8]) -> Result<ClientHelloOffsets, ClientHelloOffsetsError> {
    let payload_spans = collect_tls_record_payload_spans(buffer)?;
    if payload_spans.len() == 1 {
        return build_offsets(parse_client_hello_layout_in_record(buffer)?);
    }

    let flattened_len = payload_spans.iter().map(|span| span.end.saturating_sub(span.start)).sum::<usize>();
    let mut flattened = Vec::with_capacity(flattened_len);
    for span in &payload_spans {
        flattened.extend_from_slice(&buffer[span.start..span.end]);
    }
    let flattened_layout = parse_client_hello_layout_in_handshake(&flattened)?;
    let layout = map_flattened_layout(buffer, &payload_spans, flattened_layout)?;
    build_offsets(layout)
}

fn build_offsets(layout: ParsedClientHelloLayout) -> Result<ClientHelloOffsets, ClientHelloOffsetsError> {
    let server_name_extension = layout.extensions.iter().find(|extension| extension.ext_type == 0x0000);
    let alpn_extension = layout.extensions.iter().find(|extension| extension.ext_type == 0x0010);
    let sni_hostname = parse_server_name_hostname(&layout, server_name_extension)?;
    validate_alpn_extension(&layout, alpn_extension)?;

    Ok(ClientHelloOffsets {
        handshake_start: layout.handshake_start,
        extensions_start: layout.extensions_start,
        extensions_end: layout.extensions_end,
        server_name_extension_start: server_name_extension.map(|extension| extension.type_offset),
        sni_hostname,
        alpn_extension_start: alpn_extension.map(|extension| extension.type_offset),
    })
}

fn parse_client_hello_layout_in_record(buffer: &[u8]) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
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

fn parse_client_hello_layout_in_handshake(buffer: &[u8]) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
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

    let mut extensions = Vec::new();
    let mut ext_cursor = extensions_start;
    while ext_cursor < extensions_end {
        let ext_type = read_u16(buffer, ext_cursor).ok_or(ClientHelloOffsetsError::InvalidExtensionListLength)?;
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

    Ok(ParsedClientHelloLayout {
        raw: buffer.to_vec(),
        payload_spans: vec![base..base + buffer.len()],
        handshake_start: base,
        extensions_start: base + extensions_start,
        extensions_end: base + extensions_end,
        extensions,
    })
}

fn collect_tls_record_payload_spans(buffer: &[u8]) -> Result<Vec<Range<usize>>, ClientHelloOffsetsError> {
    if buffer.len() < 5 {
        return Err(ClientHelloOffsetsError::TruncatedRecord);
    }

    let mut spans = Vec::new();
    let mut cursor = 0usize;
    while cursor < buffer.len() {
        let header = buffer.get(cursor..cursor + 5).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
        if !is_valid_record_header(header.try_into().expect("checked header len")) {
            return Err(ClientHelloOffsetsError::InvalidRecordHeader);
        }
        let record_len = read_u16(buffer, cursor + 3).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
        let payload_start = cursor + 5;
        let payload_end = payload_start.checked_add(record_len).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
        if payload_end > buffer.len() {
            return Err(ClientHelloOffsetsError::TruncatedRecord);
        }
        spans.push(payload_start..payload_end);
        cursor = payload_end;
    }
    Ok(spans)
}

fn map_flattened_layout(
    raw: &[u8],
    payload_spans: &[Range<usize>],
    layout: ParsedClientHelloLayout,
) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
    let extensions = layout
        .extensions
        .into_iter()
        .map(|extension| {
            Ok(ParsedExtension {
                ext_type: extension.ext_type,
                type_offset: map_flattened_offset(payload_spans, extension.type_offset)?,
                data_offset: map_flattened_offset(payload_spans, extension.data_offset)?,
                data_end: map_flattened_offset(payload_spans, extension.data_end)?,
            })
        })
        .collect::<Result<Vec<_>, ClientHelloOffsetsError>>()?;

    Ok(ParsedClientHelloLayout {
        raw: raw.to_vec(),
        payload_spans: payload_spans.to_vec(),
        handshake_start: map_flattened_offset(payload_spans, layout.handshake_start)?,
        extensions_start: map_flattened_offset(payload_spans, layout.extensions_start)?,
        extensions_end: map_flattened_offset(payload_spans, layout.extensions_end)?,
        extensions,
    })
}

fn map_flattened_offset(payload_spans: &[Range<usize>], offset: usize) -> Result<usize, ClientHelloOffsetsError> {
    let total_len = payload_spans.iter().map(|span| span.end.saturating_sub(span.start)).sum::<usize>();
    if offset > total_len {
        return Err(ClientHelloOffsetsError::InvalidHandshakeLength);
    }
    if offset == total_len {
        return payload_spans.last().map(|span| span.end).ok_or(ClientHelloOffsetsError::InvalidHandshakeLength);
    }

    let mut cursor = 0usize;
    for span in payload_spans {
        let span_len = span.end.saturating_sub(span.start);
        if offset < cursor + span_len {
            return Ok(span.start + (offset - cursor));
        }
        cursor += span_len;
    }
    Err(ClientHelloOffsetsError::InvalidHandshakeLength)
}

fn parse_server_name_hostname(
    layout: &ParsedClientHelloLayout,
    extension: Option<&ParsedExtension>,
) -> Result<Option<Range<usize>>, ClientHelloOffsetsError> {
    let Some(extension) = extension else {
        return Ok(None);
    };
    let data_len = extension_payload_len(layout, extension);
    if data_len < 2 {
        return Err(ClientHelloOffsetsError::InvalidServerNameExtension);
    }

    let list_len = read_u16_range(layout, extension, 0).ok_or(ClientHelloOffsetsError::InvalidServerNameExtension)?;
    if list_len != data_len.saturating_sub(2) {
        return Err(ClientHelloOffsetsError::InvalidServerNameExtension);
    }

    let mut cursor = 2usize;
    while cursor < data_len {
        let entry_header_end = cursor + 3;
        if entry_header_end > data_len {
            return Err(ClientHelloOffsetsError::InvalidServerNameExtension);
        }
        let name_type =
            read_u8_range(layout, extension, cursor).ok_or(ClientHelloOffsetsError::InvalidServerNameExtension)?;
        let name_len =
            read_u16_range(layout, extension, cursor + 1).ok_or(ClientHelloOffsetsError::InvalidServerNameExtension)?;
        let name_start = cursor + 3;
        let name_end = name_start.checked_add(name_len).ok_or(ClientHelloOffsetsError::InvalidServerNameExtension)?;
        if name_end > data_len {
            return Err(ClientHelloOffsetsError::InvalidServerNameExtension);
        }
        if name_type == 0 {
            let start = map_extension_relative_offset(layout, extension, name_start)
                .ok_or(ClientHelloOffsetsError::InvalidServerNameExtension)?;
            let end = map_extension_relative_offset(layout, extension, name_end)
                .ok_or(ClientHelloOffsetsError::InvalidServerNameExtension)?;
            return Ok(Some(start..end));
        }
        cursor = name_end;
    }

    Ok(None)
}

fn validate_alpn_extension(
    layout: &ParsedClientHelloLayout,
    extension: Option<&ParsedExtension>,
) -> Result<(), ClientHelloOffsetsError> {
    let Some(extension) = extension else {
        return Ok(());
    };
    let data_len = extension_payload_len(layout, extension);
    if data_len < 2 {
        return Err(ClientHelloOffsetsError::InvalidAlpnExtension);
    }
    let list_len = read_u16_range(layout, extension, 0).ok_or(ClientHelloOffsetsError::InvalidAlpnExtension)?;
    if list_len != data_len.saturating_sub(2) {
        return Err(ClientHelloOffsetsError::InvalidAlpnExtension);
    }

    let mut cursor = 2usize;
    while cursor < data_len {
        let protocol_len =
            usize::from(read_u8_range(layout, extension, cursor).ok_or(ClientHelloOffsetsError::InvalidAlpnExtension)?);
        cursor = cursor.checked_add(1 + protocol_len).ok_or(ClientHelloOffsetsError::InvalidAlpnExtension)?;
        if cursor > data_len {
            return Err(ClientHelloOffsetsError::InvalidAlpnExtension);
        }
    }
    Ok(())
}

fn read_u16(buffer: &[u8], offset: usize) -> Option<usize> {
    let bytes = buffer.get(offset..offset + 2)?;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]) as usize)
}

fn read_u24(buffer: &[u8], offset: usize) -> Option<usize> {
    let bytes = buffer.get(offset..offset + 3)?;
    Some(((bytes[0] as usize) << 16) | ((bytes[1] as usize) << 8) | bytes[2] as usize)
}

fn read_u16_range(layout: &ParsedClientHelloLayout, extension: &ParsedExtension, offset: usize) -> Option<usize> {
    let bytes = extension_data(layout, extension, offset, 2)?;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]) as usize)
}

fn read_u8_range(layout: &ParsedClientHelloLayout, extension: &ParsedExtension, offset: usize) -> Option<u8> {
    extension_data(layout, extension, offset, 1).and_then(|bytes| bytes.first().copied())
}

fn extension_data(
    layout: &ParsedClientHelloLayout,
    extension: &ParsedExtension,
    offset: usize,
    len: usize,
) -> Option<Vec<u8>> {
    let total_len = extension_payload_len(layout, extension);
    if offset.checked_add(len)? > total_len {
        return None;
    }
    let mut remaining_skip = offset;
    let mut output = Vec::with_capacity(len);

    for span in &layout.payload_spans {
        let overlap_start = span.start.max(extension.data_offset);
        let overlap_end = span.end.min(extension.data_end);
        if overlap_start >= overlap_end {
            continue;
        }
        let overlap_len = overlap_end - overlap_start;
        if remaining_skip >= overlap_len {
            remaining_skip -= overlap_len;
            continue;
        }
        let chunk_start = overlap_start + remaining_skip;
        let available = overlap_end - chunk_start;
        let take = available.min(len - output.len());
        output.extend_from_slice(layout.raw.get(chunk_start..chunk_start + take)?);
        remaining_skip = 0;
        if output.len() == len {
            return Some(output);
        }
    }

    None
}

fn extension_payload_len(layout: &ParsedClientHelloLayout, extension: &ParsedExtension) -> usize {
    layout
        .payload_spans
        .iter()
        .map(|span| {
            let overlap_start = span.start.max(extension.data_offset);
            let overlap_end = span.end.min(extension.data_end);
            overlap_end.saturating_sub(overlap_start)
        })
        .sum()
}

fn map_extension_relative_offset(
    layout: &ParsedClientHelloLayout,
    extension: &ParsedExtension,
    relative_offset: usize,
) -> Option<usize> {
    if relative_offset > extension_payload_len(layout, extension) {
        return None;
    }

    let mut cursor = 0usize;
    for span in &layout.payload_spans {
        let overlap_start = span.start.max(extension.data_offset);
        let overlap_end = span.end.min(extension.data_end);
        if overlap_start >= overlap_end {
            continue;
        }
        let overlap_len = overlap_end - overlap_start;
        if relative_offset < cursor + overlap_len {
            return Some(overlap_start + (relative_offset - cursor));
        }
        cursor += overlap_len;
    }

    (relative_offset == cursor).then_some(extension.data_end)
}

fn is_valid_record_header(header: [u8; 5]) -> bool {
    header[0] == 0x16 && header[1] == 0x03 && header[2] <= 0x04
}
