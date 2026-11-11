//! Bounded inner ServerHello detection across TLS records and transport reads.

const RECORD_HEADER_LEN: usize = 5;
const MAX_RECORD_PAYLOAD: usize = 16_384;
// Fixed fields, at most 32 session-id bytes, and the u16 extension vector.
const MAX_HELLO_BODY: usize = 2 + 32 + 1 + 32 + 2 + 1 + 2 + u16::MAX as usize;

#[derive(Default)]
pub(super) struct ServerHelloFilter {
    header: [u8; RECORD_HEADER_LEN],
    header_len: usize,
    record_remaining: usize,
    handshake: Vec<u8>,
}

impl ServerHelloFilter {
    fn finish(&mut self, eligible: bool) -> Option<bool> {
        self.handshake = Vec::new();
        Some(eligible)
    }
}

/// Returns None until the ServerHello is complete, or false when splice is unsafe.
pub(super) fn filter_server_hello(state: &mut ServerHelloFilter, mut produced: &[u8]) -> Option<bool> {
    while !produced.is_empty() {
        if state.record_remaining == 0 {
            let take = (RECORD_HEADER_LEN - state.header_len).min(produced.len());
            state.header[state.header_len..state.header_len + take].copy_from_slice(&produced[..take]);
            state.header_len += take;
            produced = &produced[take..];
            if state.header_len < RECORD_HEADER_LEN {
                return None;
            }
            state.header_len = 0;
            state.record_remaining = usize::from(u16::from_be_bytes([state.header[3], state.header[4]]));
            if state.header[..3] != [22, 3, 3] || !(1..=MAX_RECORD_PAYLOAD).contains(&state.record_remaining) {
                return state.finish(false);
            }
        }
        let needed = if state.handshake.len() < 4 {
            4 - state.handshake.len()
        } else {
            4 + body_len(&state.handshake) - state.handshake.len()
        };
        let take = needed.min(state.record_remaining).min(produced.len());
        state.handshake.extend_from_slice(&produced[..take]);
        state.record_remaining -= take;
        produced = &produced[take..];
        if state.handshake.len() >= 4 {
            let length = body_len(&state.handshake);
            if state.handshake[0] != 2 || !(38..=MAX_HELLO_BODY).contains(&length) {
                return state.finish(false);
            }
            if state.handshake.len() == length + 4 {
                // ServerHello must end at the record boundary before the key change.
                let eligible = state.record_remaining == 0 && splice_eligible(&state.handshake[4..]).unwrap_or(false);
                return state.finish(eligible);
            }
        }
    }
    None
}

fn body_len(handshake: &[u8]) -> usize {
    (usize::from(handshake[1]) << 16) | (usize::from(handshake[2]) << 8) | usize::from(handshake[3])
}

fn splice_eligible(body: &[u8]) -> Option<bool> {
    if body.get(..2)? != [3, 3] {
        return Some(false);
    }
    let session_id_len = usize::from(*body.get(34)?);
    if session_id_len > 32 {
        return Some(false);
    }
    let tail = body.get(35 + session_id_len..)?;
    let cipher = u16::from_be_bytes([*tail.first()?, *tail.get(1)?]);
    if !(0x1301..=0x1304).contains(&cipher) || *tail.get(2)? != 0 {
        return Some(false);
    }
    let extension_len = usize::from(u16::from_be_bytes([*tail.get(3)?, *tail.get(4)?]));
    let mut extensions = tail.get(5..)?;
    if extensions.len() != extension_len {
        return Some(false);
    }
    let mut tls13 = None;
    while !extensions.is_empty() {
        let header = extensions.get(..4)?;
        let kind = u16::from_be_bytes([header[0], header[1]]);
        let length = usize::from(u16::from_be_bytes([header[2], header[3]]));
        let data = extensions.get(4..4 + length)?;
        if kind == 43 && tls13.replace(data == [3, 4]).is_some() {
            return Some(false);
        }
        extensions = &extensions[4 + length..];
    }
    Some(tls13.unwrap_or(false))
}
