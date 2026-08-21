//! Forge a JA3/JA4-preserving decoy ClientHello.
//!
//! [`forge_decoy_client_hello`] takes a real, **record-framed** TLS
//! ClientHello (TLS record header `16 03 0x` + handshake type `01`) and returns
//! a byte-for-byte copy in which ONLY the `server_name` (SNI) host string has
//! been swapped for the decoy hostname, with every dependent length field
//! corrected.
//!
//! ## Input framing
//!
//! The input MUST be a single TLS plaintext record carrying one
//! `ClientHello` handshake message:
//!
//! ```text
//! [0..5]   TLS record header:  content_type=0x16  version=0x0301  record_len(u16)
//! [5]      handshake type:     0x01 (ClientHello)
//! [6..9]   handshake length:   u24
//! ...      legacy_version, random, session_id, cipher_suites, compression
//! ...      extensions_len(u16) then the extensions block, incl. SNI (type 0x0000)
//! ```
//!
//! This is exactly the framing `ripdpi_packets::is_tls_client_hello` accepts
//! (`16 03 .. .. .. 01`). Bare handshake messages (no record header) are NOT
//! supported here; the relay always sees the record-framed bytes off the wire.
//!
//! ## What is preserved
//!
//! cipher_suites, the extension *order* and *count*, GREASE values, key shares,
//! supported_versions, ALPN — every JA3/JA4-relevant byte — are copied
//! verbatim. The only mutated regions are the SNI host bytes plus the six
//! length fields that frame them.

use ripdpi_packets::{TlsMarkerInfo, parse_tls_client_hello_layout};

use crate::error::SpoofError;

/// Offsets of the fixed TLS record + handshake length fields (record framing).
const RECORD_LEN_OFFSET: usize = 3; // u16
const HANDSHAKE_LEN_OFFSET: usize = 6; // u24

/// Produce a decoy ClientHello: a copy of `real_client_hello` with the SNI
/// host string replaced by `decoy_sni` and all dependent length fields fixed.
///
/// A single trailing dot on `decoy_sni` (dot-terminated FQDN) is normalized
/// out of the wire form: RFC 6066 §3 forbids it in the ServerName.
///
/// `decoy_sni` may be longer or shorter than the original host; the output is
/// resized accordingly. Returns [`SpoofError::MalformedClientHello`] if the
/// input is not a record-framed ClientHello with a `server_name` extension,
/// and [`SpoofError::UnsupportedServerNameList`] if the list does not hold
/// exactly one entry — it never panics on malformed input.
///
/// Note: caller is expected to have validated `decoy_sni` already
/// (`crate::config::validate_decoy_sni`); this function performs no further
/// hostname-syntax checking beyond the trailing-dot normalization — it does
/// the byte surgery only.
pub fn forge_decoy_client_hello(real_client_hello: &[u8], decoy_sni: &str) -> Result<Vec<u8>, SpoofError> {
    let layout = parse_tls_client_hello_layout(real_client_hello).ok_or(SpoofError::MalformedClientHello)?;
    let markers = layout.markers;

    ensure_single_server_name_entry(real_client_hello, &markers)?;

    // RFC 6066 §3 forbids a trailing dot in ServerName; accept dot-terminated
    // FQDN input but normalize it out of the wire form.
    let decoy_sni = decoy_sni.strip_suffix('.').unwrap_or(decoy_sni);
    if decoy_sni.is_empty() {
        return Err(SpoofError::EmptySni);
    }

    let host_start = markers.host_start;
    let host_end = markers.host_end;
    // Defensive: the parser should guarantee these, but never trust offsets
    // blindly before slicing — fail closed rather than panic.
    if host_start > host_end || host_end > real_client_hello.len() {
        return Err(SpoofError::MalformedClientHello);
    }

    let old_host_len = host_end - host_start;
    let new_host = decoy_sni.as_bytes();
    let new_host_len = new_host.len();

    // Rebuild the buffer: [.. up to host_start] + decoy host + [host_end ..].
    let mut out = Vec::with_capacity(real_client_hello.len() + new_host_len - old_host_len);
    out.extend_from_slice(&real_client_hello[..host_start]);
    out.extend_from_slice(new_host);
    out.extend_from_slice(&real_client_hello[host_end..]);

    // Signed byte delta to fold into every length field that frames the host.
    let delta = new_host_len as isize - old_host_len as isize;
    if delta != 0 {
        patch_lengths(&mut out, &markers, delta)?;
    }

    Ok(out)
}

/// RFC 6066 §3 requires exactly one ServerName entry per list. The length
/// surgery patches only the first HostName length field, so a multi-entry
/// list would come out malformed — reject it instead of emitting garbage.
///
/// `markers.sni_ext_start` points at the ServerNameList length (u16); entries
/// follow as `name_type(1) + host_name_len(2) + name`.
fn ensure_single_server_name_entry(buf: &[u8], markers: &TlsMarkerInfo) -> Result<(), SpoofError> {
    let list_len = buf
        .get(markers.sni_ext_start..markers.sni_ext_start + 2)
        .map(|b| u16::from_be_bytes([b[0], b[1]]) as usize)
        .ok_or(SpoofError::MalformedClientHello)?;
    let list_end = markers.sni_ext_start + 2 + list_len;
    if list_end > buf.len() {
        return Err(SpoofError::MalformedClientHello);
    }

    let mut off = markers.sni_ext_start + 2;
    let mut entries = 0usize;
    while off + 3 <= list_end {
        let name_len = u16::from_be_bytes([buf[off + 1], buf[off + 2]]) as usize;
        off += 3 + name_len;
        entries += 1;
    }
    if off != list_end {
        return Err(SpoofError::MalformedClientHello);
    }
    if entries != 1 {
        return Err(SpoofError::UnsupportedServerNameList);
    }
    Ok(())
}

/// Apply `delta` to the six length fields that bracket the SNI host string.
///
/// Fields (all big-endian), per the TLS ClientHello + `server_name` extension
/// layout (mirrors `ripdpi-packets`'s `resize_sni` / `adjust_tls_lengths`):
///
/// 1. TLS record length         — u16 @ offset 3
/// 2. Handshake message length  — u24 @ offset 6
/// 3. Extensions block length   — u16 @ `markers.ext_len_start`
/// 4. SNI extension data length — u16 @ `markers.sni_ext_start - 2`
/// 5. ServerNameList length     — u16 @ `markers.sni_ext_start`
/// 6. HostName length           — u16 @ `markers.sni_ext_start + 3`
///
/// Note `markers.sni_ext_start` points PAST the 4-byte SNI extension header
/// (it equals `type_offset + 4`, i.e. the ServerNameList length field) — see
/// `ripdpi_packets::tls_nom::to_marker_info`. The extension's own length field
/// therefore sits two bytes *before* it, and the HostName length three bytes
/// after.
fn patch_lengths(buf: &mut [u8], markers: &TlsMarkerInfo, delta: isize) -> Result<(), SpoofError> {
    let sni_ext_len_off = markers.sni_ext_start.checked_sub(2).ok_or(SpoofError::MalformedClientHello)?;
    apply_u16(buf, RECORD_LEN_OFFSET, delta)?;
    apply_u24(buf, HANDSHAKE_LEN_OFFSET, delta)?;
    apply_u16(buf, markers.ext_len_start, delta)?;
    apply_u16(buf, sni_ext_len_off, delta)?;
    apply_u16(buf, markers.sni_ext_start, delta)?;
    apply_u16(buf, markers.sni_ext_start + 3, delta)?;
    Ok(())
}

/// Read a big-endian u16 at `off`, add `delta`, write it back. Fails closed on
/// out-of-range offset or under/overflow.
fn apply_u16(buf: &mut [u8], off: usize, delta: isize) -> Result<(), SpoofError> {
    let bytes = buf.get(off..off + 2).ok_or(SpoofError::MalformedClientHello)?;
    let cur = u16::from_be_bytes([bytes[0], bytes[1]]) as isize;
    let next = cur + delta;
    if next < 0 || next > u16::MAX as isize {
        return Err(SpoofError::MalformedClientHello);
    }
    let next = (next as u16).to_be_bytes();
    buf[off] = next[0];
    buf[off + 1] = next[1];
    Ok(())
}

/// Read a big-endian u24 at `off`, add `delta`, write it back. Fails closed on
/// out-of-range offset or under/overflow.
fn apply_u24(buf: &mut [u8], off: usize, delta: isize) -> Result<(), SpoofError> {
    let bytes = buf.get(off..off + 3).ok_or(SpoofError::MalformedClientHello)?;
    let cur = ((bytes[0] as isize) << 16) | ((bytes[1] as isize) << 8) | (bytes[2] as isize);
    let next = cur + delta;
    if !(0..=0x00FF_FFFF).contains(&next) {
        return Err(SpoofError::MalformedClientHello);
    }
    let next = next as u32;
    buf[off] = ((next >> 16) & 0xFF) as u8;
    buf[off + 1] = ((next >> 8) & 0xFF) as u8;
    buf[off + 2] = (next & 0xFF) as u8;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_packets::parse_tls;

    /// Build a minimal but realistic record-framed ClientHello carrying a
    /// single SNI extension for `host`, plus a couple of GREASE/other
    /// extensions so the "JA3-relevant bytes unchanged" assertion is meaningful.
    fn build_client_hello(host: &str) -> Vec<u8> {
        build_client_hello_with_hosts(&[host])
    }

    /// Like [`build_client_hello`], but with full control over the
    /// ServerNameList entries (for multi-entry rejection tests).
    fn build_client_hello_with_hosts(hosts: &[&str]) -> Vec<u8> {
        // --- SNI extension (type 0x0000) ---
        // server_name entries: name_type(1) + host_name_len(2) + host each.
        let mut sni_ext_data = Vec::new();
        let list_len: usize = hosts.iter().map(|h| 3 + h.len()).sum();
        sni_ext_data.extend_from_slice(&(list_len as u16).to_be_bytes());
        for host in hosts {
            let host = host.as_bytes();
            sni_ext_data.push(0x00); // name_type = host_name
            sni_ext_data.extend_from_slice(&(host.len() as u16).to_be_bytes());
            sni_ext_data.extend_from_slice(host);
        }

        let mut extensions = Vec::new();
        // SNI ext: type 0x0000, len, data
        extensions.extend_from_slice(&0x0000u16.to_be_bytes());
        extensions.extend_from_slice(&(sni_ext_data.len() as u16).to_be_bytes());
        extensions.extend_from_slice(&sni_ext_data);
        // A GREASE extension (type 0x0a0a), empty data — JA3-relevant ordering.
        extensions.extend_from_slice(&0x0a0au16.to_be_bytes());
        extensions.extend_from_slice(&0x0000u16.to_be_bytes());
        // supported_versions (0x002b), 3-byte body: list_len(1)=2, 0x0304.
        extensions.extend_from_slice(&0x002bu16.to_be_bytes());
        extensions.extend_from_slice(&0x0003u16.to_be_bytes());
        extensions.push(0x02);
        extensions.extend_from_slice(&0x0304u16.to_be_bytes());

        // --- handshake body ---
        let mut body = Vec::new();
        body.extend_from_slice(&[0x03, 0x03]); // legacy_version TLS1.2
        body.extend_from_slice(&[0xAB; 32]); // random
        body.push(0x00); // session_id len = 0
        // cipher_suites: len(2) + two suites (JA3-relevant).
        body.extend_from_slice(&0x0004u16.to_be_bytes());
        body.extend_from_slice(&0x1301u16.to_be_bytes()); // TLS_AES_128_GCM_SHA256
        body.extend_from_slice(&0xc02fu16.to_be_bytes()); // ECDHE_RSA_AES128_GCM
        // compression_methods: len(1)=1, null.
        body.push(0x01);
        body.push(0x00);
        // extensions block: len(2) + extensions.
        body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        body.extend_from_slice(&extensions);

        // --- handshake header ---
        let mut handshake = Vec::new();
        handshake.push(0x01); // ClientHello
        let hs_len = body.len();
        handshake.push(((hs_len >> 16) & 0xFF) as u8);
        handshake.push(((hs_len >> 8) & 0xFF) as u8);
        handshake.push((hs_len & 0xFF) as u8);
        handshake.extend_from_slice(&body);

        // --- TLS record header ---
        let mut record = Vec::new();
        record.push(0x16); // handshake
        record.extend_from_slice(&[0x03, 0x01]); // version
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);
        record
    }

    /// Extract the JA3-relevant byte sequence (everything except the SNI host
    /// region and the six length fields we deliberately patch). We compare the
    /// cipher-suite bytes and the extension *type* tags, which is what JA3/JA4
    /// hash over.
    fn ja3_relevant_fingerprint(buf: &[u8]) -> Vec<u8> {
        // cipher_suites: at body offset — after record(5)+hs hdr(4)+ver(2)+
        // random(32)+session_id_len(1)=44. cipher_suites_len u16 @ 44.
        let cs_len = u16::from_be_bytes([buf[44], buf[45]]) as usize;
        let mut fp = Vec::new();
        fp.extend_from_slice(&buf[46..46 + cs_len]); // cipher suite bytes
        // Walk extensions and collect their type tags in order.
        let layout = parse_tls_client_hello_layout(buf).expect("layout");
        for ext in &layout.extensions {
            fp.extend_from_slice(&ext.ext_type.to_be_bytes());
        }
        fp
    }

    #[test]
    fn output_reparses_to_decoy() {
        let input = build_client_hello("real.example.com");
        let out = forge_decoy_client_hello(&input, "www.wikipedia.org").unwrap();
        let parsed = parse_tls(&out).expect("decoy host parses");
        assert_eq!(parsed, b"www.wikipedia.org");
    }

    #[test]
    fn ja3_relevant_bytes_unchanged() {
        let input = build_client_hello("real.example.com");
        let out = forge_decoy_client_hello(&input, "decoy.test").unwrap();
        // cipher suites + extension type ordering identical pre/post surgery.
        assert_eq!(ja3_relevant_fingerprint(&input), ja3_relevant_fingerprint(&out));
    }

    #[test]
    fn only_sni_region_and_lengths_differ() {
        // Same-length decoy so NO length field changes: the ONLY differing
        // bytes must be inside the SNI host region.
        let input = build_client_hello("aaaa.example.com"); // 16 chars
        let decoy = "bbbb.example.org"; // also 16 chars
        let out = forge_decoy_client_hello(&input, decoy).unwrap();
        assert_eq!(input.len(), out.len());

        let markers = parse_tls_client_hello_layout(&input).unwrap().markers;
        for i in 0..input.len() {
            if i >= markers.host_start && i < markers.host_end {
                continue; // host region is allowed to differ
            }
            assert_eq!(input[i], out[i], "byte {i} outside SNI region changed");
        }
    }

    #[test]
    fn decoy_longer_works() {
        let input = build_client_hello("a.io");
        let out = forge_decoy_client_hello(&input, "a-much-longer.decoy.example.com").unwrap();
        assert_eq!(parse_tls(&out).unwrap(), b"a-much-longer.decoy.example.com");
        // Record length field must reflect the growth.
        let new_record_len = u16::from_be_bytes([out[3], out[4]]) as usize;
        assert_eq!(new_record_len, out.len() - 5);
    }

    #[test]
    fn decoy_shorter_works() {
        let input = build_client_hello("a-much-longer.real.example.com");
        let out = forge_decoy_client_hello(&input, "x.io").unwrap();
        assert_eq!(parse_tls(&out).unwrap(), b"x.io");
        let new_record_len = u16::from_be_bytes([out[3], out[4]]) as usize;
        assert_eq!(new_record_len, out.len() - 5);
    }

    #[test]
    fn malformed_input_returns_err() {
        assert_eq!(forge_decoy_client_hello(&[], "x.com"), Err(SpoofError::MalformedClientHello));
        assert_eq!(forge_decoy_client_hello(&[0x16, 0x03, 0x01, 0x00], "x.com"), Err(SpoofError::MalformedClientHello));
        // A ServerHello (handshake type 0x02), not a ClientHello.
        let mut not_ch = build_client_hello("real.example.com");
        not_ch[5] = 0x02;
        assert_eq!(forge_decoy_client_hello(&not_ch, "x.com"), Err(SpoofError::MalformedClientHello));
    }

    #[test]
    fn multi_entry_server_name_list_rejected() {
        // RFC 6066 allows exactly one entry; the surgery only patches the
        // first HostName length, so anything else must fail closed instead
        // of producing a malformed rewrite.
        let input = build_client_hello_with_hosts(&["real.example.com", "second.example.net"]);
        assert_eq!(forge_decoy_client_hello(&input, "decoy.test"), Err(SpoofError::UnsupportedServerNameList));
    }

    #[test]
    fn trailing_dot_fqdn_normalized() {
        let input = build_client_hello("real.example.com");
        let dotted = forge_decoy_client_hello(&input, "www.wikipedia.org.").unwrap();
        let plain = forge_decoy_client_hello(&input, "www.wikipedia.org").unwrap();
        assert_eq!(dotted, plain, "trailing dot must be stripped from the wire form");
        assert_eq!(parse_tls(&dotted).unwrap(), b"www.wikipedia.org");
    }

    #[test]
    fn dot_only_decoy_rejected() {
        let input = build_client_hello("real.example.com");
        assert_eq!(forge_decoy_client_hello(&input, "."), Err(SpoofError::EmptySni));
    }

    #[test]
    fn length_overflow_fails_closed() {
        // A decoy long enough to push the record past the u16 length field
        // must fail closed instead of wrapping a length counter.
        let input = build_client_hello("a.io");
        let decoy = format!("{}.example.com", "x".repeat(70_000));
        assert_eq!(forge_decoy_client_hello(&input, &decoy), Err(SpoofError::MalformedClientHello));
    }

    #[test]
    fn large_but_fitting_decoy_succeeds() {
        // Just under the u16 record-length ceiling: the surgery must still
        // work and the output must reparse.
        let input = build_client_hello("a.io");
        let decoy = format!("{}.example.com", "x".repeat(64_000));
        let out = forge_decoy_client_hello(&input, &decoy).unwrap();
        assert_eq!(parse_tls(&out).unwrap(), decoy.as_bytes());
    }

    #[test]
    fn no_sni_extension_returns_err() {
        // Hand-build a ClientHello with NO extensions block at all.
        let mut body = Vec::new();
        body.extend_from_slice(&[0x03, 0x03]);
        body.extend_from_slice(&[0xAB; 32]);
        body.push(0x00); // session id
        body.extend_from_slice(&0x0002u16.to_be_bytes());
        body.extend_from_slice(&0x1301u16.to_be_bytes());
        body.push(0x01);
        body.push(0x00);
        body.extend_from_slice(&0x0000u16.to_be_bytes()); // extensions len = 0

        let mut handshake = vec![0x01];
        let hs_len = body.len();
        handshake.push(((hs_len >> 16) & 0xFF) as u8);
        handshake.push(((hs_len >> 8) & 0xFF) as u8);
        handshake.push((hs_len & 0xFF) as u8);
        handshake.extend_from_slice(&body);

        let mut record = vec![0x16, 0x03, 0x01];
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);

        assert_eq!(forge_decoy_client_hello(&record, "x.com"), Err(SpoofError::MalformedClientHello));
    }
}
