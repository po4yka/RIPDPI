use std::ops::Range;

use super::offset_mapping::{extension_payload_len, map_extension_relative_offset};
use super::{ClientHelloOffsetsError, ParsedClientHelloLayout, ParsedExtension};

pub(crate) fn parse_server_name_hostname(
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

pub(crate) fn validate_alpn_extension(
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
