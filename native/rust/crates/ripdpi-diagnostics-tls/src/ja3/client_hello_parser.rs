use std::borrow::Cow;

use super::grease::is_grease;

const MAX_RECORD_PAYLOAD: usize = 16_384;
const HANDSHAKE_HEADER_LEN: usize = 4;
const MAX_SESSION_ID_LEN: usize = 32;
const MAX_CLIENT_HELLO_BODY: usize = 2 + 32 + (1 + MAX_SESSION_ID_LEN) + (2 + 65_534) + (1 + 255) + (2 + 65_535);

pub(super) struct ClientHelloFields {
    pub(super) version: u16,
    pub(super) cipher_suites: Vec<u16>,
    pub(super) extensions: Vec<u16>,
    pub(super) supported_groups: Vec<u16>,
    pub(super) ec_point_formats: Vec<u16>,
}

pub(super) fn parse_client_hello(data: &[u8]) -> Option<ClientHelloFields> {
    let handshake = first_client_hello(data)?;
    let mut body = &handshake[HANDSHAKE_HEADER_LEN..];
    let version = read_u16(&mut body)?;
    take(&mut body, 32)?;
    if take_byte_vector(&mut body)?.len() > MAX_SESSION_ID_LEN {
        return None;
    }

    let cipher_suites = ordered_ids(take_word_vector(&mut body)?)?;
    if take_byte_vector(&mut body)?.is_empty() {
        return None;
    }

    let mut fields = ClientHelloFields {
        version,
        cipher_suites,
        extensions: Vec::new(),
        supported_groups: Vec::new(),
        ec_point_formats: Vec::new(),
    };
    if !body.is_empty() {
        let extensions = take_word_vector(&mut body)?;
        if !body.is_empty() {
            return None;
        }
        parse_extensions(extensions, &mut fields)?;
    }

    Some(fields)
}

fn first_client_hello(mut records: &[u8]) -> Option<Cow<'_, [u8]>> {
    let mut handshake = Cow::Borrowed(take_handshake_record(&mut records)?);
    while handshake.len() < HANDSHAKE_HEADER_LEN {
        handshake.to_mut().extend_from_slice(take_handshake_record(&mut records)?);
    }

    let [kind, high, middle, low] = *handshake.first_chunk::<HANDSHAKE_HEADER_LEN>()?;
    let body_len = (usize::from(high) << 16) | usize::from(u16::from_be_bytes([middle, low]));
    if kind != 0x01 || body_len > MAX_CLIENT_HELLO_BODY {
        return None;
    }
    let end = HANDSHAKE_HEADER_LEN + body_len;

    while handshake.len() < end {
        let fragment = take_handshake_record(&mut records)?;
        let count = (end - handshake.len()).min(fragment.len());
        handshake.to_mut().extend_from_slice(&fragment[..count]);
    }

    Some(match handshake {
        Cow::Borrowed(payload) => Cow::Borrowed(&payload[..end]),
        Cow::Owned(mut payload) => {
            payload.truncate(end);
            Cow::Owned(payload)
        }
    })
}

fn take_handshake_record<'a>(data: &mut &'a [u8]) -> Option<&'a [u8]> {
    let (header, rest) = data.split_first_chunk::<5>()?;
    let [kind, _, _, high, low] = *header;
    let len = usize::from(u16::from_be_bytes([high, low]));
    if kind != 0x16 || !(1..=MAX_RECORD_PAYLOAD).contains(&len) {
        return None;
    }
    *data = rest;
    take(data, len)
}

fn parse_extensions(mut extensions: &[u8], fields: &mut ClientHelloFields) -> Option<()> {
    while !extensions.is_empty() {
        let kind = read_u16(&mut extensions)?;
        let mut value = take_word_vector(&mut extensions)?;
        if is_grease(kind) {
            continue;
        }
        fields.extensions.push(kind);

        match kind {
            0x000a => {
                let groups = take_word_vector(&mut value)?;
                if !value.is_empty() {
                    return None;
                }
                fields.supported_groups.extend(ordered_ids(groups)?);
            }
            0x000b => {
                let formats = take_byte_vector(&mut value)?;
                if formats.is_empty() || !value.is_empty() {
                    return None;
                }
                fields.ec_point_formats.extend(formats.iter().copied().map(u16::from));
            }
            _ => {}
        }
    }
    Some(())
}

fn ordered_ids(data: &[u8]) -> Option<Vec<u16>> {
    let (ids, remainder) = data.as_chunks::<2>();
    if ids.is_empty() || !remainder.is_empty() {
        return None;
    }

    Some(ids.iter().copied().map(u16::from_be_bytes).filter(|id| !is_grease(*id)).collect())
}

fn take_word_vector<'a>(data: &mut &'a [u8]) -> Option<&'a [u8]> {
    let len = usize::from(read_u16(data)?);
    take(data, len)
}

fn take_byte_vector<'a>(data: &mut &'a [u8]) -> Option<&'a [u8]> {
    let (&len, rest) = data.split_first()?;
    *data = rest;
    take(data, usize::from(len))
}

fn read_u16(data: &mut &[u8]) -> Option<u16> {
    let (word, rest) = data.split_first_chunk::<2>()?;
    *data = rest;
    Some(u16::from_be_bytes(*word))
}

fn take<'a>(data: &mut &'a [u8], len: usize) -> Option<&'a [u8]> {
    let (value, rest) = data.split_at_checked(len)?;
    *data = rest;
    Some(value)
}
