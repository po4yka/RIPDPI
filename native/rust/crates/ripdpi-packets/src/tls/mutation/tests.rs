use super::super::edit::adjust_tls_lengths;
use super::*;
use crate::fake_profiles::{TlsFakeProfile, tls_fake_profile_bytes};
use crate::util::{read_u16, read_u24};

type Extensions = Vec<(u16, Vec<u8>)>;

/// Independent strict walk of a single-record ClientHello: declared lengths must
/// match the bytes, no extension type may repeat, and `pre_shared_key` must be last.
fn strict_extensions(buf: &[u8]) -> Result<Extensions, String> {
    let record_len = read_u16(buf, 3).ok_or("no record header")?;
    let handshake_len = read_u24(buf, 6).ok_or("no handshake header")?;
    if record_len + 5 != buf.len() || handshake_len + 4 != record_len {
        return Err(format!("record {record_len} / handshake {handshake_len} vs {} bytes", buf.len()));
    }
    let ext_len_offset = crate::tls_nom::parse_client_hello_record(buf).ok_or("unparsable")?.ext_len_offset;
    let ext_len = read_u16(buf, ext_len_offset).ok_or("no extension block")?;
    if ext_len_offset + 2 + ext_len != buf.len() {
        return Err(format!("extension block {ext_len} does not end the record"));
    }
    let mut cursor = ext_len_offset + 2;
    let mut extensions: Extensions = Vec::new();
    while cursor < buf.len() {
        let kind = read_u16(buf, cursor).ok_or("cut extension header")? as u16;
        let len = read_u16(buf, cursor + 2).ok_or("cut extension header")?;
        let end = cursor + 4 + len;
        if end > buf.len() {
            return Err(format!("extension {kind:#06x} overruns the block"));
        }
        if extensions.iter().any(|(seen, _)| *seen == kind) {
            return Err(format!("extension {kind:#06x} repeats"));
        }
        extensions.push((kind, buf[cursor + 4..end].to_vec()));
        cursor = end;
    }
    if let Some(at) = extensions.iter().position(|(kind, _)| *kind == 0x0029)
        && at + 1 != extensions.len()
    {
        return Err("pre_shared_key is not the last extension".to_owned());
    }
    Ok(extensions)
}

/// Append an extension after the last one and fix the three length fields.
fn append_extension(record: &[u8], kind: u16, data: &[u8]) -> Vec<u8> {
    let ext_len_offset = crate::tls_nom::parse_client_hello_record(record).expect("parse").ext_len_offset;
    let mut out = record.to_vec();
    out.extend_from_slice(&kind.to_be_bytes());
    out.extend_from_slice(&(data.len() as u16).to_be_bytes());
    out.extend_from_slice(data);
    assert!(adjust_tls_lengths(&mut out, ext_len_offset, 4 + data.len() as isize));
    out
}

fn without(extensions: &[(u16, Vec<u8>)], skipped: &[u16]) -> Extensions {
    extensions.iter().filter(|(kind, _)| !skipped.contains(kind)).cloned().collect()
}

fn padding_len(extensions: &[(u16, Vec<u8>)]) -> usize {
    extensions.iter().find(|(kind, _)| *kind == 0x0015).map_or(0, |(_, data)| data.len())
}

const PROFILES: [TlsFakeProfile; 6] = [
    TlsFakeProfile::IanaFirefox,
    TlsFakeProfile::GoogleChrome,
    TlsFakeProfile::GoogleChromeHrr,
    TlsFakeProfile::VkChrome,
    TlsFakeProfile::SberbankChrome,
    TlsFakeProfile::RutrackerKyber,
];

#[test]
fn grow_keeps_following_extensions_intact() {
    let template = tls_fake_profile_bytes(TlsFakeProfile::IanaFirefox);
    let input = append_extension(template, 0x1234, &[0x00]);
    let before = strict_extensions(&input).expect("input");
    let padding_at = before.iter().position(|(kind, _)| *kind == 0x0015).expect("template has padding");
    assert!(padding_at + 1 < before.len(), "padding must not be last");

    let mutation = tune_tls_padding_size_like_c(&input, input.len() + 20);

    assert_eq!(mutation.rc, 0);
    assert_eq!(mutation.bytes.len(), input.len() + 20);
    let after = strict_extensions(&mutation.bytes).expect("grown");
    assert_eq!(without(&after, &[0x0015]), without(&before, &[0x0015]));
    assert_eq!(padding_len(&after), padding_len(&before) + 20);
}

#[test]
fn grow_without_padding_needs_room_for_the_header() {
    let input = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
    assert_eq!(padding_len(&strict_extensions(input).unwrap()), 0);

    for grow in 1..4 {
        let mutation = tune_tls_padding_size_like_c(input, input.len() + grow);
        assert_eq!((mutation.rc, mutation.bytes.as_slice()), (-1, input), "grow {grow}");
    }
    let mutation = tune_tls_padding_size_like_c(input, input.len() + 4);
    assert_eq!(mutation.rc, 0);
    assert_eq!(strict_extensions(&mutation.bytes).unwrap().last().map(|(kind, _)| *kind), Some(0x0015));
}

#[test]
fn new_padding_goes_before_pre_shared_key() {
    let psk = [0u8; 8];
    // Grow path: no padding in the template.
    let chrome = append_extension(tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome), 0x0029, &psk);
    let grown = tune_tls_padding_size_like_c(&chrome, chrome.len() + 40);
    assert_eq!(grown.rc, 0);
    let types: Vec<u16> = strict_extensions(&grown.bytes).expect("grown").iter().map(|(kind, _)| *kind).collect();
    assert_eq!(types[types.len() - 2..], [0x0015, 0x0029]);

    // Shrink path: the ECH offer is dropped and the gap is filled with padding.
    let shrunk = tune_tls_padding_size_like_c(&chrome, 517 + 4 + psk.len());
    assert_eq!(shrunk.rc, 0);
    let types: Vec<u16> = strict_extensions(&shrunk.bytes).expect("shrunk").iter().map(|(kind, _)| *kind).collect();
    assert_eq!(types[types.len() - 2..], [0x0015, 0x0029]);
}

#[test]
fn tune_padding_keeps_untouched_extensions_for_every_profile_and_target() {
    for profile in PROFILES {
        let template = tls_fake_profile_bytes(profile);
        let variants = [
            template.to_vec(),
            append_extension(template, 0x1234, &[0x00]),
            append_extension(template, 0x0029, &[0u8; 8]),
        ];
        for input in variants {
            let before = strict_extensions(&input).expect("input");
            for delta in (-300isize..=300).filter(|delta| *delta != 0) {
                let target = (input.len() as isize + delta) as usize;
                let mutation = tune_tls_padding_size_like_c(&input, target);
                if mutation.rc != 0 {
                    assert_eq!(mutation.bytes, input, "{profile:?} {delta}");
                    continue;
                }
                assert_eq!(mutation.bytes.len(), target, "{profile:?} {delta}");
                let after =
                    strict_extensions(&mutation.bytes).unwrap_or_else(|err| panic!("{profile:?} {delta}: {err}"));
                // Shrinking may also drop the ECH offer; nothing else may change.
                let skipped: &[u16] = if delta < 0 { &[0x0015, 0xfe0d] } else { &[0x0015] };
                assert_eq!(without(&after, skipped), without(&before, skipped), "{profile:?} {delta}");
            }
        }
    }
}

#[test]
fn merge_records_never_claims_bytes_past_a_truncated_last_record() {
    let record = |declared: usize, fill: u8, present: usize| {
        let mut bytes = vec![0x16, 3, 1, (declared >> 8) as u8, declared as u8];
        bytes.extend(std::iter::repeat_n(fill, present));
        bytes
    };
    for missing in 0..=5usize {
        let mut buf = [record(10, 1, 10), record(10, 2, 10), record(10, 3, 10 - missing)].concat();
        let n = buf.len();

        let removed = super::super::edit::merge_tls_records(&mut buf, n);

        let merged_end = 5 + read_u16(&buf, 3).unwrap();
        assert!(merged_end <= n - removed, "missing {missing}: record ends at {merged_end}, data at {}", n - removed);
    }
}
