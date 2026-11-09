use std::borrow::Cow;

use hickory_proto::op::{Edns, Message};
use hickory_proto::rr::RecordType;
use hickory_proto::rr::rdata::opt::{EdnsCode, EdnsOption};

/// RFC 8467 4.1: pad queries to a multiple of this many octets.
const PADDING_BLOCK: usize = 128;
/// UDP payload size advertised when the resolver adds an OPT itself (DNS Flag Day 2020).
const ADDED_OPT_PAYLOAD: u16 = 1232;
const OPT_CODE_KEEPALIVE: u16 = 11;
const OPT_CODE_PADDING: u16 = 12;

/// How the resolver changed the requester's OPT, so the reply can be put back.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EdnsChange {
    Unchanged,
    Normalized,
    Added,
}

fn is_keepalive_or_padding(code: EdnsCode) -> bool {
    matches!(u16::from(code), OPT_CODE_KEEPALIVE | OPT_CODE_PADDING)
}

/// Strips client EDNS options 11 and 12 and, when `pad` is set, adds an RFC 8467 block-128 padding option.
///
/// The query is returned unchanged when it does not parse, carries a signature, or needs no change.
pub(crate) fn prepare_query(query: &[u8], pad: bool) -> (Cow<'_, [u8]>, EdnsChange) {
    let unchanged = || (Cow::Borrowed(query), EdnsChange::Unchanged);
    let Ok(mut message) = Message::from_vec(query) else {
        return unchanged();
    };
    // A TSIG or SIG(0) signature covers the original bytes; without hickory's dnssec feature TSIG stays in additionals.
    if message.signature.is_some()
        || message.additionals.iter().any(|record| matches!(record.record_type(), RecordType::SIG | RecordType::TSIG))
    {
        return unchanged();
    }

    let had_opt = message.edns.is_some();
    let mut edns = message.edns.take().unwrap_or_else(|| {
        let mut added = Edns::new();
        added.set_max_payload(ADDED_OPT_PAYLOAD);
        added
    });
    let before = edns.options().options.len();
    edns.options_mut().options.retain(|(code, _)| !is_keepalive_or_padding(*code));
    let stripped = edns.options().options.len() != before;
    if !pad && !stripped {
        return unchanged();
    }
    let limit = usize::from(edns.max_payload().max(512));
    message.edns = Some(edns);

    let mut padded = false;
    if pad {
        if let Some(edns) = message.edns.as_mut() {
            edns.options_mut().options.push((EdnsCode::Padding, EdnsOption::Unknown(OPT_CODE_PADDING, Vec::new())));
        }
        let Ok(base) = message.to_vec() else {
            return unchanged();
        };
        let fill = (PADDING_BLOCK - base.len() % PADDING_BLOCK) % PADDING_BLOCK;
        if base.len() + fill <= limit {
            if let Some((_, option)) = message.edns.as_mut().and_then(|edns| edns.options_mut().options.last_mut()) {
                *option = EdnsOption::Unknown(OPT_CODE_PADDING, vec![0; fill]);
            }
            padded = true;
        } else if let Some(edns) = message.edns.as_mut() {
            edns.options_mut().options.pop();
        }
    }
    if !padded && !stripped {
        return unchanged();
    }
    match message.to_vec() {
        Ok(bytes) => (Cow::Owned(bytes), if had_opt { EdnsChange::Normalized } else { EdnsChange::Added }),
        Err(_) => unchanged(),
    }
}

/// Puts the reply back in the shape the requester expects (RFC 6891 7). Re-encodes only when something changes.
pub(crate) fn restore_response(response: Vec<u8>, change: EdnsChange) -> Vec<u8> {
    if change == EdnsChange::Unchanged {
        return response;
    }
    let Ok(mut message) = Message::from_vec(&response) else {
        return response;
    };
    // An extended RCODE (BADVERS, BADCOOKIE) lives in the OPT; removing it would turn the code into another one.
    if message.metadata.response_code.high() > 0 {
        return response;
    }
    let Some(edns) = message.edns.as_mut() else {
        return response;
    };
    match change {
        EdnsChange::Unchanged => return response,
        EdnsChange::Added => message.edns = None,
        EdnsChange::Normalized => {
            let before = edns.options().options.len();
            edns.options_mut().remove(EdnsCode::Padding);
            if edns.options().options.len() == before {
                return response;
            }
        }
    }
    message.to_vec().unwrap_or(response)
}

#[cfg(test)]
mod tests {
    use super::*;

    const QUESTION: [u8; 27] = [
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // header
        0x01, b'a', 0x07, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 0x00, // a.example
        0x00, 0x01, 0x00, 0x01, // A IN
    ];

    fn with_opt(ar_count: u8, opt: &[u8]) -> Vec<u8> {
        let mut bytes = QUESTION.to_vec();
        bytes[11] = ar_count;
        bytes.extend_from_slice(opt);
        bytes
    }

    /// OPT: root, TYPE 41, CLASS 1232, TTL flags `do_flag`, RDLEN, options.
    fn opt_rr(do_flag: bool, options: &[u8]) -> Vec<u8> {
        let mut rr = vec![0x00, 0x00, 0x29, 0x04, 0xd0, 0x00, 0x00, if do_flag { 0x80 } else { 0x00 }, 0x00];
        rr.extend_from_slice(&(options.len() as u16).to_be_bytes());
        rr.extend_from_slice(options);
        rr
    }

    const COOKIE: [u8; 12] = [0x00, 0x0a, 0x00, 0x08, 1, 2, 3, 4, 5, 6, 7, 8];
    const KEEPALIVE: [u8; 6] = [0x00, 0x0b, 0x00, 0x02, 0x00, 0x64];
    const PADDING2: [u8; 6] = [0x00, 0x0c, 0x00, 0x02, 0x00, 0x00];

    fn options(parts: &[&[u8]]) -> Vec<u8> {
        parts.concat()
    }

    #[test]
    fn adds_block_128_padding_opt_to_query_without_opt() {
        let (out, change) = prepare_query(&QUESTION, true);
        let mut expected =
            with_opt(1, &[0x00, 0x00, 0x29, 0x04, 0xd0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x5a, 0x00, 0x0c, 0x00, 0x56]);
        expected.extend(std::iter::repeat_n(0u8, 86));
        assert_eq!(change, EdnsChange::Added);
        assert_eq!(expected.len(), 128);
        assert_eq!(out.as_ref(), expected.as_slice());
    }

    #[test]
    fn strips_keepalive_and_padding_without_padding() {
        let query = with_opt(1, &opt_rr(true, &options(&[&COOKIE, &KEEPALIVE, &PADDING2])));
        let expected = with_opt(1, &opt_rr(true, &COOKIE));
        let (out, change) = prepare_query(&query, false);
        assert_eq!(change, EdnsChange::Normalized);
        assert_eq!(out.as_ref(), expected.as_slice());
    }

    #[test]
    fn strips_and_pads_keeping_other_options_first() {
        let query = with_opt(1, &opt_rr(true, &options(&[&COOKIE, &KEEPALIVE, &PADDING2])));
        let (out, change) = prepare_query(&query, true);
        assert_eq!(change, EdnsChange::Normalized);
        assert_eq!(out.len() % 128, 0);
        // OPT starts at 27; options start after 11 fixed OPT bytes.
        assert_eq!(out[27 + 7], 0x80, "DO bit kept");
        assert_eq!(&out[38..38 + COOKIE.len()], &COOKIE);
        let pad_at = 38 + COOKIE.len();
        assert_eq!(&out[pad_at..pad_at + 2], &[0x00, 0x0c]);
        let pad_len = usize::from(u16::from_be_bytes([out[pad_at + 2], out[pad_at + 3]]));
        assert_eq!(pad_at + 4 + pad_len, out.len(), "padding option is last");
        assert!(out[pad_at + 4..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn tsig_signed_query_is_returned_unchanged() {
        // TSIG RR (RFC 8945 4.2): owner "k", TYPE 250, CLASS ANY, TTL 0, RDATA = hmac-sha256 name, time, fudge,
        // MAC size 0, original ID, error 0, other len 0. A signed query must not be re-encoded.
        let mut rdata = vec![0x0b];
        rdata.extend_from_slice(b"hmac-sha256");
        rdata.push(0x00);
        rdata.extend_from_slice(&[
            0x00, 0x00, 0x65, 0x00, 0x00, 0x00, 0x01, 0x2c, 0x00, 0x00, 0x12, 0x34, 0x00, 0x00, 0x00, 0x00,
        ]);
        let mut tsig = vec![0x01, b'k', 0x00, 0x00, 0xfa, 0x00, 0xff, 0x00, 0x00, 0x00, 0x00];
        tsig.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
        tsig.extend_from_slice(&rdata);
        let query = with_opt(1, &tsig);
        assert!(Message::from_vec(&query).is_ok(), "the TSIG branch, not the parse failure, must keep the query");
        let (out, change) = prepare_query(&query, true);
        assert_eq!(change, EdnsChange::Unchanged);
        assert_eq!(out.as_ref(), query.as_slice());
    }

    #[test]
    fn restore_keeps_a_reply_with_an_extended_rcode() {
        // BADVERS (16): RCODE low bits 0, OPT TTL extended RCODE 1.
        let reply = response(1, &[0x00, 0x00, 0x29, 0x04, 0xd0, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00]);
        assert_eq!(restore_response(reply.clone(), EdnsChange::Added), reply);
    }

    #[test]
    fn unparseable_query_is_returned_unchanged() {
        let truncated = [0x12, 0x34, 0x01, 0x00, 0x00];
        let (out, change) = prepare_query(&truncated, true);
        assert_eq!(change, EdnsChange::Unchanged);
        assert_eq!(out.as_ref(), &truncated);
    }

    #[test]
    fn padding_is_skipped_when_it_would_exceed_the_advertised_payload() {
        // CLASS 512 and a 480-byte option: 27 + 11 + 4 + 480 = 522, the next multiple of 128 is 640 > 512.
        let mut big = vec![0xfd, 0xe9, 0x01, 0xe0];
        big.extend(std::iter::repeat_n(0u8, 480));
        let mut query = with_opt(1, &opt_rr(false, &big));
        query[29..31].copy_from_slice(&512u16.to_be_bytes());
        let (out, change) = prepare_query(&query, true);
        assert_eq!(change, EdnsChange::Unchanged);
        assert_eq!(out.as_ref(), query.as_slice());
    }

    fn response(ar_count: u8, opt: &[u8]) -> Vec<u8> {
        let mut bytes = with_opt(ar_count, opt);
        bytes[2] = 0x81;
        bytes[3] = 0x80;
        bytes
    }

    #[test]
    fn restore_removes_opt_that_the_resolver_added() {
        let reply = response(1, &opt_rr(false, &PADDING2));
        let restored = restore_response(reply, EdnsChange::Added);
        assert_eq!(restored, response(0, &[]));
    }

    #[test]
    fn restore_removes_padding_but_keeps_other_options() {
        let ede = [0x00, 0x0f, 0x00, 0x02, 0x00, 0x00];
        let reply = response(1, &opt_rr(false, &options(&[&ede, &PADDING2])));
        let restored = restore_response(reply, EdnsChange::Normalized);
        assert_eq!(restored, response(1, &opt_rr(false, &ede)));
    }

    #[test]
    fn restore_leaves_unchanged_and_unparseable_replies_alone() {
        let reply = response(1, &opt_rr(false, &PADDING2));
        assert_eq!(restore_response(reply.clone(), EdnsChange::Unchanged), reply);
        let broken = vec![0x12, 0x34, 0x81];
        assert_eq!(restore_response(broken.clone(), EdnsChange::Added), broken);
        assert_eq!(restore_response(broken.clone(), EdnsChange::Normalized), broken);
    }
}
