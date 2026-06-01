//! JA3 drift guard for the bundled fake-TLS ClientHello `.bin` profiles.
//!
//! The static fake-TLS profiles are real captured browser ClientHellos. These
//! tests pin each profile's JA3 *pre-hash string* (the
//! `version,ciphers,extensions,groups,ec_point_formats` form, GREASE filtered),
//! so any silent drift in a blob fails here with a human-diffable diff. The
//! string is pinned, never the MD5 hash, so the failure is legible.
//!
//! NOTE: this duplicates the production JA3 parser from
//! `ripdpi-diagnostics-tls/src/ja3/{client_hello_parser.rs,grease.rs}` inline.
//! `ripdpi-packets` does not (and must not) depend on `ripdpi-diagnostics-tls`,
//! and adding a workspace dev-dep on `ripdpi-packets` to `ripdpi-diagnostics-tls`
//! churns `native/rust/Cargo.lock` (forbidden). The duplication is the price of
//! zero-dep parity; the algorithm below is a line-for-line port and the cipher-
//! count cross-check against PROVENANCE.md guards against the port drifting.

use super::{TlsFakeProfile, tls_fake_profile_bytes};

// ---------------------------------------------------------------------------
// Inline port of the production JA3 parser (GREASE filter + field extraction).
// ---------------------------------------------------------------------------

/// GREASE values have the pattern `0x?a?a` where high and low bytes are equal
/// and `(byte & 0x0f) == 0x0a`. Ported from `ja3/grease.rs::is_grease`.
fn is_grease(value: u16) -> bool {
    let hi = (value >> 8) as u8;
    let lo = value as u8;
    hi == lo && (hi & 0x0f) == 0x0a
}

fn read_u16(data: &[u8], pos: &mut usize) -> Option<u16> {
    if *pos + 2 > data.len() {
        return None;
    }
    let value = u16::from_be_bytes([data[*pos], data[*pos + 1]]);
    *pos += 2;
    Some(value)
}

fn read_u8(data: &[u8], pos: &mut usize) -> Option<u8> {
    if *pos >= data.len() {
        return None;
    }
    let value = data[*pos];
    *pos += 1;
    Some(value)
}

fn skip(data: &[u8], pos: &mut usize, n: usize) -> Option<()> {
    if *pos + n > data.len() {
        return None;
    }
    *pos += n;
    Some(())
}

fn read_u24(data: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos + 3 > data.len() {
        return None;
    }
    let value = (u32::from(data[*pos]) << 16) | (u32::from(data[*pos + 1]) << 8) | u32::from(data[*pos + 2]);
    *pos += 3;
    Some(value)
}

fn join_decimal(values: &[u16]) -> String {
    values.iter().map(std::string::ToString::to_string).collect::<Vec<_>>().join("-")
}

/// Compute the JA3 pre-hash string from a raw TLS record beginning at the
/// handshake content-type byte. Line-for-line port of the production
/// `parse_client_hello` + `compute_ja3_string`.
fn compute_ja3_string(data: &[u8]) -> Option<String> {
    let mut pos = 0;

    // TLS record header: type(1) + version(2) + length(2)
    let content_type = read_u8(data, &mut pos)?;
    if content_type != 0x16 {
        return None;
    }
    skip(data, &mut pos, 2)?; // record version (ignored for JA3)
    let _record_length = read_u16(data, &mut pos)?;

    // Handshake header: type(1) + length(3)
    let handshake_type = read_u8(data, &mut pos)?;
    if handshake_type != 0x01 {
        return None;
    }
    let _handshake_length = read_u24(data, &mut pos)?;

    // ClientHello body
    let version = read_u16(data, &mut pos)?;
    skip(data, &mut pos, 32)?; // random

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
                        let fmt = u16::from(read_u8(data, &mut pos)?);
                        ec_point_formats.push(fmt);
                    }
                }
            }

            pos = ext_data_start + ext_len;
        }
    }

    Some(format!(
        "{},{},{},{},{}",
        version,
        join_decimal(&cipher_suites),
        join_decimal(&extensions),
        join_decimal(&supported_groups),
        join_decimal(&ec_point_formats),
    ))
}

// ---------------------------------------------------------------------------
// Pinned fingerprints.
// ---------------------------------------------------------------------------

/// Pinned JA3 pre-hash string for `IanaFirefox` (`iana.org`, Firefox ~115-125).
const JA3_IANA_FIREFOX: &str = "771,4866-4867-4865-49196-49200-159-52393-52392-52394-49195-49199-158-49188-49192-107-49187-49191-103-49162-49172-57-49161-49171-51-157-156-61-60-53-47-255,0-11-10-13172-16-22-23-49-13-43-45-51-21,29-23-30-25-24,0-1-2";
/// Pinned JA3 pre-hash string for `GoogleChrome` (`www.google.com`, Chrome ~115-120).
const JA3_GOOGLE_CHROME: &str = "771,4865-4867-4866-49195-49199-52393-52392-49196-49200-49162-49161-49171-49172-156-157-47-53,0-23-65281-10-11-35-16-5-34-18-51-43-13-45-28-27-65037,29-23-24-25-256-257,0";
/// Pinned JA3 pre-hash string for `VkChrome` (`vk.com`, Chrome ~110-124).
const JA3_VK_CHROME: &str = "771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,13-17513-43-0-23-5-10-16-45-27-65281-35-11-51-18-21,29-23-24,0";
/// Pinned JA3 pre-hash string for `SberbankChrome` (`online.sberbank.ru`).
const JA3_SBERBANK_CHROME: &str = "771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,16-0-51-35-27-17513-11-18-5-10-43-45-65281-23-13-21,29-23-24,0";
/// Pinned JA3 pre-hash string for `RutrackerKyber` (`rutracker.org`, Chrome >= 124).
const JA3_RUTRACKER_KYBER: &str = "771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,11-35-45-13-65037-10-5-0-65281-51-23-43-18-17513-27-16,25497-29-23-24,0";
/// Pinned JA3 pre-hash string for `BigsizeIana` (synthetic oversized variant of `IanaFirefox`; identical to `IanaFirefox`).
const JA3_BIGSIZE_IANA: &str = "771,4866-4867-4865-49196-49200-159-52393-52392-52394-49195-49199-158-49188-49192-107-49187-49191-103-49162-49172-57-49161-49171-51-157-156-61-60-53-47-255,0-11-10-13172-16-22-23-49-13-43-45-51-21,29-23-30-25-24,0-1-2";

/// Count of `-`-separated cipher-suite IDs in a JA3 pre-hash string (field 2).
fn cipher_count(ja3: &str) -> usize {
    let ciphers = ja3.split(',').nth(1).expect("ja3 string has a cipher field");
    if ciphers.is_empty() { 0 } else { ciphers.split('-').count() }
}

#[test]
fn fake_tls_profiles_pin_ja3_string() {
    // NOTE: `CompatDefault` (the workspace default `DEFAULT_FAKE_TLS`) is a
    // truncated synthetic blob — its declared extensions-length field exceeds
    // the remaining bytes, so the production JA3 parser correctly returns `None`
    // for it (see `compat_default_does_not_parse`). It is deliberately excluded —
    // pinning a parse-failure would pin garbage. The drift guard covers every
    // captured-browser profile.
    let cases = [
        (TlsFakeProfile::IanaFirefox, JA3_IANA_FIREFOX),
        (TlsFakeProfile::GoogleChrome, JA3_GOOGLE_CHROME),
        (TlsFakeProfile::GoogleChromeHrr, JA3_GOOGLE_CHROME),
        (TlsFakeProfile::VkChrome, JA3_VK_CHROME),
        (TlsFakeProfile::SberbankChrome, JA3_SBERBANK_CHROME),
        (TlsFakeProfile::RutrackerKyber, JA3_RUTRACKER_KYBER),
        (TlsFakeProfile::BigsizeIana, JA3_BIGSIZE_IANA),
    ];

    for (profile, expected) in cases {
        let bytes = tls_fake_profile_bytes(profile);
        let actual =
            compute_ja3_string(bytes).unwrap_or_else(|| panic!("profile {profile:?} should parse as a ClientHello"));
        assert_eq!(actual, expected, "JA3 drift for {profile:?}");
    }
}

/// Pin the fact that `CompatDefault` (the truncated synthetic `DEFAULT_FAKE_TLS`)
/// does NOT parse as a ClientHello — its declared extensions-length exceeds the
/// remaining bytes. If a future edit makes it parseable this fires, prompting a
/// pinned-string entry rather than silently leaving it uncovered.
#[test]
fn compat_default_does_not_parse() {
    assert!(
        compute_ja3_string(tls_fake_profile_bytes(TlsFakeProfile::CompatDefault)).is_none(),
        "CompatDefault is a truncated synthetic blob and must not parse as a ClientHello",
    );
}

/// `GoogleChromeHrr` is wired to the same blob as `GoogleChrome`, so their JA3
/// strings MUST be byte-identical.
#[test]
fn google_chrome_and_hrr_are_identical() {
    let chrome =
        compute_ja3_string(tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome)).expect("GoogleChrome should parse");
    let hrr = compute_ja3_string(tls_fake_profile_bytes(TlsFakeProfile::GoogleChromeHrr))
        .expect("GoogleChromeHrr should parse");
    assert_eq!(chrome, hrr, "GoogleChrome and GoogleChromeHrr must share a JA3 string");
}

/// Integrity cross-check against PROVENANCE.md documented cipher counts (GREASE
/// is filtered by the JA3 parser, so counts exclude any GREASE placeholder).
/// IanaFirefox: PROVENANCE documents 31 ciphers (no GREASE).
/// VkChrome / SberbankChrome: 16 incl. GREASE → 15 after GREASE filtering.
#[test]
fn cipher_counts_match_provenance() {
    let firefox =
        compute_ja3_string(tls_fake_profile_bytes(TlsFakeProfile::IanaFirefox)).expect("IanaFirefox should parse");
    assert_eq!(cipher_count(&firefox), 31, "IanaFirefox cipher count vs PROVENANCE.md");

    let vk = compute_ja3_string(tls_fake_profile_bytes(TlsFakeProfile::VkChrome)).expect("VkChrome should parse");
    assert_eq!(cipher_count(&vk), 15, "VkChrome cipher count (16 incl. GREASE - 1 GREASE)");
}
