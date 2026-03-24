//! Nom-based TLS ClientHello parser returning typed values with byte offsets.
//!
//! Unlike `tls-parser`, this module returns raw byte offsets into the original
//! buffer, making it compatible with the write-path mutation functions in `tls.rs`.

use crate::types::TlsMarkerInfo;
use nom::bytes::complete::{tag, take};
use nom::number::complete::{be_u16, be_u8};
use nom::IResult;
use nom::Offset;

/// A single TLS extension with its byte offset in the original buffer.
#[derive(Debug, Clone)]
pub(crate) struct RawExtension<'a> {
    pub ext_type: u16,
    /// Byte offset of the 2-byte extension type field within the parse base.
    pub type_offset: usize,
    /// Extension payload (past the 4-byte type + length header).
    pub data: &'a [u8],
}

/// Parsed ClientHello with byte offsets for all key landmarks.
#[derive(Debug, Clone)]
pub(crate) struct ClientHelloParsed<'a> {
    /// Byte offset of the 2-byte extensions-list length field within the parse base.
    pub ext_len_offset: usize,
    /// All extensions found in the ClientHello, in order.
    pub extensions: Vec<RawExtension<'a>>,
}

/// Parse a big-endian 24-bit unsigned integer.
fn be_u24(input: &[u8]) -> IResult<&[u8], usize> {
    let (input, bytes) = take(3usize)(input)?;
    Ok((input, ((bytes[0] as usize) << 16) | ((bytes[1] as usize) << 8) | bytes[2] as usize))
}

/// Internal nom parser for a ClientHello handshake body (no record header).
/// `original` is the base slice for offset calculations.
fn parse_client_hello_handshake_nom<'a>(
    original: &'a [u8],
    input: &'a [u8],
) -> IResult<&'a [u8], ClientHelloParsed<'a>> {
    // Handshake type: ClientHello (0x01)
    let (input, _) = tag(&[0x01])(input)?;
    // Handshake length (3 bytes)
    let (input, hs_len) = be_u24(input)?;
    let (_, input) = if input.len() >= hs_len { Ok((&input[hs_len..], &input[..hs_len])) } else { Ok((&[][..], input)) }?;
    // Client version (2 bytes)
    let (input, _version) = be_u16(input)?;
    // Random (32 bytes)
    let (input, _random) = take(32usize)(input)?;
    // Session ID: length (1 byte) + data
    let (input, sid_len) = be_u8(input)?;
    let (input, _session_id) = take(sid_len as usize)(input)?;
    // Cipher suites: length (2 bytes) + data
    let (input, cs_len) = be_u16(input)?;
    let (input, _cipher_suites) = take(cs_len as usize)(input)?;
    // Compression methods: length (1 byte) + data
    let (input, comp_len) = be_u8(input)?;
    let (input, _compression) = take(comp_len as usize)(input)?;

    // Extensions list -- use min(declared, available) to tolerate truncated templates
    let ext_len_offset = original.offset(input);
    let (input, ext_total_len) = be_u16(input)?;
    let actual_ext_len = core::cmp::min(ext_total_len as usize, input.len());
    let (rest, mut ext_data) = take(actual_ext_len)(input)?;

    let mut extensions = Vec::new();
    while ext_data.len() >= 4 {
        let type_offset = original.offset(ext_data);
        let (rem, ext_type) = be_u16(ext_data)?;
        let (rem, data_len) = be_u16(rem)?;
        if rem.len() < data_len as usize {
            break;
        }
        let (rem, data) = take(data_len as usize)(rem)?;
        extensions.push(RawExtension { ext_type, type_offset, data });
        ext_data = rem;
    }

    Ok((rest, ClientHelloParsed { ext_len_offset, extensions }))
}

/// Parse a TLS ClientHello from a bare handshake message (no record header).
/// Offsets are relative to the start of `input`.
pub(crate) fn parse_client_hello_handshake(input: &[u8]) -> Option<ClientHelloParsed<'_>> {
    parse_client_hello_handshake_nom(input, input).ok().map(|(_, parsed)| parsed)
}

/// Parse a TLS ClientHello from a full TLS record (with 5-byte record header).
/// Offsets are relative to the start of `input` (i.e., include the record header).
pub(crate) fn parse_client_hello_record(input: &[u8]) -> Option<ClientHelloParsed<'_>> {
    let (remaining, _) = parse_record_header(input).ok()?;
    let mut parsed = parse_client_hello_handshake_nom(input, remaining).ok().map(|(_, p)| p)?;
    // Offsets are already correct because `original` was set to `input` (full buffer).
    // But we passed `remaining` (after record header) as the parse input, so offsets
    // computed via `original.offset(...)` are relative to `input`, which is what we want.
    let _ = &mut parsed; // no adjustment needed -- original == input
    Some(parsed)
}

/// Parse the 5-byte TLS record header.
fn parse_record_header(input: &[u8]) -> IResult<&[u8], (u8, u16, u16)> {
    let (input, content_type) = be_u8(input)?;
    let (input, version) = be_u16(input)?;
    let (input, length) = be_u16(input)?;
    Ok((input, (content_type, version, length)))
}

/// Find the first extension matching `ext_type`.
pub(crate) fn find_extension<'a>(parsed: &'a ClientHelloParsed<'a>, ext_type: u16) -> Option<&'a RawExtension<'a>> {
    parsed.extensions.iter().find(|e| e.ext_type == ext_type)
}

/// Find the byte offset of an extension's type field. Returns the offset of
/// the 2-byte type field within the original buffer.
pub(crate) fn find_extension_offset(parsed: &ClientHelloParsed<'_>, ext_type: u16) -> Option<usize> {
    find_extension(parsed, ext_type).map(|e| e.type_offset)
}

/// Convert a parsed ClientHello into `TlsMarkerInfo`.
///
/// The `sni_ext_start` field points past the 4-byte extension header (type + length)
/// into the SNI extension body, matching the convention in `tls.rs`.
pub(crate) fn to_marker_info(parsed: &ClientHelloParsed<'_>, buf_len: usize) -> Option<TlsMarkerInfo> {
    let sni = find_extension(parsed, 0x0000)?;
    // SNI extension body: [list_len: u16] [name_type: u8] [host_len: u16] [hostname...]
    if sni.data.len() < 5 {
        return None;
    }
    let host_len = u16::from_be_bytes([sni.data[3], sni.data[4]]) as usize;
    let sni_ext_start = sni.type_offset + 4;
    // host_start = type(2) + len(2) + list_len(2) + name_type(1) + host_len(2) = +9
    let host_start = sni.type_offset + 9;
    let host_end = host_start + host_len;
    if host_end > buf_len {
        return None;
    }
    Some(TlsMarkerInfo { ext_len_start: parsed.ext_len_offset, sni_ext_start, host_start, host_end })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tls::{is_tls_client_hello, tls_marker_info};
    use crate::types::DEFAULT_FAKE_TLS;

    #[test]
    fn parse_default_fake_tls_matches_manual() {
        // DEFAULT_FAKE_TLS is truncated (record length > actual), but our parser
        // should still extract SNI since it lives early in the extensions block.
        let manual = tls_marker_info(DEFAULT_FAKE_TLS).expect("manual parser");
        let parsed = parse_client_hello_record(DEFAULT_FAKE_TLS).expect("nom parser");
        let nom_marker = to_marker_info(&parsed, DEFAULT_FAKE_TLS.len()).expect("nom marker");
        assert_eq!(manual, nom_marker, "marker info mismatch on DEFAULT_FAKE_TLS");
    }

    #[test]
    fn parse_golden_fixtures_match_manual() {
        let fixtures: &[(&[u8], &str)] = &[
            (include_bytes!("../tests/fixtures/curl_tls12_example_com.bin"), "tls12_example"),
            (include_bytes!("../tests/fixtures/curl_tls13_example_com.bin"), "tls13_example"),
            (include_bytes!("../tests/fixtures/curl_auto_cloudflare_com.bin"), "auto_cloudflare"),
            (include_bytes!("../tests/fixtures/curl_tls13_github_com.bin"), "tls13_github"),
        ];

        for (data, name) in fixtures {
            assert!(is_tls_client_hello(data), "{name}: not a ClientHello");
            let manual = tls_marker_info(data).unwrap_or_else(|| panic!("{name}: manual failed"));
            let parsed = parse_client_hello_record(data).unwrap_or_else(|| panic!("{name}: nom failed"));
            let nom_marker =
                to_marker_info(&parsed, data.len()).unwrap_or_else(|| panic!("{name}: nom marker failed"));
            assert_eq!(manual, nom_marker, "{name}: marker info mismatch");
        }
    }

    #[test]
    fn parse_all_fake_profiles_match_manual() {
        use crate::fake_profiles::{tls_fake_profile_bytes, TlsFakeProfile};

        let profiles = [
            TlsFakeProfile::CompatDefault,
            TlsFakeProfile::IanaFirefox,
            TlsFakeProfile::GoogleChrome,
            TlsFakeProfile::VkChrome,
            TlsFakeProfile::SberbankChrome,
            TlsFakeProfile::RutrackerKyber,
            TlsFakeProfile::BigsizeIana,
        ];

        for profile in &profiles {
            let data = tls_fake_profile_bytes(*profile);
            let manual = tls_marker_info(data);
            let nom_result = parse_client_hello_record(data).and_then(|p| to_marker_info(&p, data.len()));
            assert_eq!(manual, nom_result, "mismatch for profile {profile:?}");
        }
    }

    #[test]
    fn find_extension_offset_matches_known_types() {
        let data = include_bytes!("../tests/fixtures/curl_auto_cloudflare_com.bin");
        let parsed = parse_client_hello_record(data.as_slice()).expect("nom parse");

        // SNI (0x0000) must be found
        assert!(find_extension_offset(&parsed, 0x0000).is_some(), "SNI not found");

        // Every extension type_offset must point to the correct type bytes in the buffer
        for ext in &parsed.extensions {
            let actual_type = u16::from_be_bytes([data[ext.type_offset], data[ext.type_offset + 1]]);
            assert_eq!(actual_type, ext.ext_type, "type_offset mismatch for ext 0x{:04x}", ext.ext_type);
        }
    }

    #[test]
    fn empty_input_returns_none() {
        assert!(parse_client_hello_record(&[]).is_none());
        assert!(parse_client_hello_handshake(&[]).is_none());
    }

    #[test]
    fn truncated_at_various_points_returns_none() {
        let data = include_bytes!("../tests/fixtures/curl_tls12_example_com.bin");
        // Truncate at every byte up to 10 -- all should return None or parse partially
        for len in 0..10 {
            assert!(parse_client_hello_record(&data[..len]).is_none(), "should fail at len={len}");
        }
    }

    #[test]
    fn handshake_variant_matches_record_minus_header() {
        let data = include_bytes!("../tests/fixtures/curl_tls13_example_com.bin");
        let record_parsed = parse_client_hello_record(data.as_slice()).expect("record parse");
        let hs_parsed = parse_client_hello_handshake(&data[5..]).expect("handshake parse");

        // Handshake variant offsets should be exactly 5 less than record variant
        assert_eq!(record_parsed.ext_len_offset, hs_parsed.ext_len_offset + 5);
        assert_eq!(record_parsed.extensions.len(), hs_parsed.extensions.len());
        for (r, h) in record_parsed.extensions.iter().zip(hs_parsed.extensions.iter()) {
            assert_eq!(r.ext_type, h.ext_type);
            assert_eq!(r.type_offset, h.type_offset + 5);
        }
    }
}
