//! JA3 TLS fingerprint computation for HTTPS strategy probes.
//!
//! JA3 captures the TLS ClientHello parameters and produces a stable MD5 hash
//! that identifies the TLS client implementation. This is used to verify that
//! different desync strategies produce distinct TLS handshakes and to detect
//! DPI fingerprint-based blocking.
//!
//! Reference: <https://github.com/salesforce/ja3>

use std::io::{self, Read, Write};

// ---------------------------------------------------------------------------
// RecordingStream -- wraps a Read+Write stream, records all writes
// ---------------------------------------------------------------------------

pub(crate) struct RecordingStream<S> {
    inner: S,
    recorded_writes: Vec<u8>,
}

impl<S> RecordingStream<S> {
    pub(crate) fn new(stream: S) -> Self {
        Self { inner: stream, recorded_writes: Vec::with_capacity(512) }
    }

    pub(crate) fn recorded_writes(&self) -> &[u8] {
        &self.recorded_writes
    }

    pub(crate) fn into_parts(self) -> (S, Vec<u8>) {
        (self.inner, self.recorded_writes)
    }
}

impl<S: Read> Read for RecordingStream<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.inner.read(buf)
    }
}

impl<S: Write> Write for RecordingStream<S> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.inner.write(buf)?;
        self.recorded_writes.extend_from_slice(&buf[..n]);
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

// ---------------------------------------------------------------------------
// ClientHello parser
// ---------------------------------------------------------------------------

struct ClientHelloFields {
    version: u16,
    cipher_suites: Vec<u16>,
    extensions: Vec<u16>,
    supported_groups: Vec<u16>,
    ec_point_formats: Vec<u16>,
}

/// Returns true if the value is a GREASE (Generate Random Extensions And
/// Sustain Extensibility) value. GREASE values have the pattern 0x?a?a where
/// the high and low bytes are equal and `(byte & 0x0f) == 0x0a`.
fn is_grease(value: u16) -> bool {
    let hi = (value >> 8) as u8;
    let lo = value as u8;
    hi == lo && (hi & 0x0f) == 0x0a
}

/// Read a big-endian u16 from `data` at `pos`, advancing `pos`.
fn read_u16(data: &[u8], pos: &mut usize) -> Option<u16> {
    if *pos + 2 > data.len() {
        return None;
    }
    let value = u16::from_be_bytes([data[*pos], data[*pos + 1]]);
    *pos += 2;
    Some(value)
}

/// Read a single byte from `data` at `pos`, advancing `pos`.
fn read_u8(data: &[u8], pos: &mut usize) -> Option<u8> {
    if *pos >= data.len() {
        return None;
    }
    let value = data[*pos];
    *pos += 1;
    Some(value)
}

/// Skip `n` bytes in `data` at `pos`.
fn skip(data: &[u8], pos: &mut usize, n: usize) -> Option<()> {
    if *pos + n > data.len() {
        return None;
    }
    *pos += n;
    Some(())
}

/// Read a u24 (3-byte big-endian) from `data` at `pos`, advancing `pos`.
fn read_u24(data: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos + 3 > data.len() {
        return None;
    }
    let value = (data[*pos] as u32) << 16 | (data[*pos + 1] as u32) << 8 | (data[*pos + 2] as u32);
    *pos += 3;
    Some(value)
}

/// Parse a TLS ClientHello from the raw bytes written during the handshake.
/// The input should begin with a TLS record header (content type 0x16).
fn parse_client_hello(data: &[u8]) -> Option<ClientHelloFields> {
    let mut pos = 0;

    // TLS record header: type(1) + version(2) + length(2)
    let content_type = read_u8(data, &mut pos)?;
    if content_type != 0x16 {
        return None; // not a handshake record
    }
    skip(data, &mut pos, 2)?; // record version (ignored for JA3)
    let _record_length = read_u16(data, &mut pos)?;

    // Handshake header: type(1) + length(3)
    let handshake_type = read_u8(data, &mut pos)?;
    if handshake_type != 0x01 {
        return None; // not ClientHello
    }
    let _handshake_length = read_u24(data, &mut pos)?;

    // ClientHello body
    let version = read_u16(data, &mut pos)?; // client version

    // Random (32 bytes)
    skip(data, &mut pos, 32)?;

    // Session ID
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
                        let fmt = read_u8(data, &mut pos)? as u16;
                        ec_point_formats.push(fmt);
                    }
                }
            }

            // Advance past any unread extension data
            pos = ext_data_start + ext_len;
        }
    }

    Some(ClientHelloFields { version, cipher_suites, extensions, supported_groups, ec_point_formats })
}

fn join_decimal(values: &[u16]) -> String {
    values.iter().map(|v| v.to_string()).collect::<Vec<_>>().join("-")
}

/// Compute the JA3 fingerprint hash from recorded TLS handshake bytes.
///
/// Returns `None` if the bytes do not contain a valid ClientHello.
pub(crate) fn compute_ja3(recorded_bytes: &[u8]) -> Option<String> {
    let fields = parse_client_hello(recorded_bytes)?;
    let ja3_string = format!(
        "{},{},{},{},{}",
        fields.version,
        join_decimal(&fields.cipher_suites),
        join_decimal(&fields.extensions),
        join_decimal(&fields.supported_groups),
        join_decimal(&fields.ec_point_formats),
    );
    Some(format!("{:x}", md5::compute(ja3_string.as_bytes())))
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_grease_filters_grease_values() {
        assert!(is_grease(0x0a0a));
        assert!(is_grease(0x1a1a));
        assert!(is_grease(0x2a2a));
        assert!(is_grease(0x3a3a));
        assert!(is_grease(0x4a4a));
        assert!(is_grease(0x5a5a));
        assert!(is_grease(0x6a6a));
        assert!(is_grease(0x7a7a));
        assert!(is_grease(0x8a8a));
        assert!(is_grease(0x9a9a));
        assert!(is_grease(0xaaaa));
        assert!(is_grease(0xbaba));
        assert!(is_grease(0xcaca));
        assert!(is_grease(0xdada));
        assert!(is_grease(0xeaea));
        assert!(is_grease(0xfafa));
    }

    #[test]
    fn is_grease_rejects_non_grease() {
        assert!(!is_grease(0x0001));
        assert!(!is_grease(0x1301));
        assert!(!is_grease(0x0a0b)); // hi != lo
        assert!(!is_grease(0x0b0b)); // (0x0b & 0x0f) == 0x0b, not 0x0a
        assert!(!is_grease(0x0000));
        assert!(!is_grease(0xffff));
    }

    /// Build a minimal valid TLS ClientHello for testing.
    fn build_test_client_hello(version: u16, cipher_suites: &[u16], extensions: &[(u16, Vec<u8>)]) -> Vec<u8> {
        let mut hello_body = Vec::new();

        // Version
        hello_body.extend_from_slice(&version.to_be_bytes());

        // Random (32 zero bytes)
        hello_body.extend_from_slice(&[0u8; 32]);

        // Session ID (empty)
        hello_body.push(0x00);

        // Cipher suites
        let cs_len = (cipher_suites.len() * 2) as u16;
        hello_body.extend_from_slice(&cs_len.to_be_bytes());
        for suite in cipher_suites {
            hello_body.extend_from_slice(&suite.to_be_bytes());
        }

        // Compression methods (just null)
        hello_body.push(0x01); // length
        hello_body.push(0x00); // null compression

        // Extensions
        if !extensions.is_empty() {
            let mut ext_bytes = Vec::new();
            for (ext_type, ext_data) in extensions {
                ext_bytes.extend_from_slice(&ext_type.to_be_bytes());
                ext_bytes.extend_from_slice(&(ext_data.len() as u16).to_be_bytes());
                ext_bytes.extend_from_slice(ext_data);
            }
            hello_body.extend_from_slice(&(ext_bytes.len() as u16).to_be_bytes());
            hello_body.extend_from_slice(&ext_bytes);
        }

        // Handshake header
        let mut handshake = Vec::new();
        handshake.push(0x01); // ClientHello
        let hs_len = hello_body.len() as u32;
        handshake.push((hs_len >> 16) as u8);
        handshake.push((hs_len >> 8) as u8);
        handshake.push(hs_len as u8);
        handshake.extend_from_slice(&hello_body);

        // TLS record header
        let mut record = Vec::new();
        record.push(0x16); // handshake
        record.extend_from_slice(&[0x03, 0x01]); // TLS 1.0 record version
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);

        record
    }

    #[test]
    fn compute_ja3_minimal_client_hello() {
        // TLS 1.2 (0x0303) with two cipher suites, no extensions
        let data = build_test_client_hello(0x0303, &[0x1301, 0x1302], &[]);
        let ja3 = compute_ja3(&data);
        assert!(ja3.is_some(), "should parse a valid ClientHello");

        let hash = ja3.unwrap();
        assert_eq!(hash.len(), 32, "MD5 hex should be 32 chars");

        // The JA3 string is "771,4865-4866,,,"
        // version=771 (0x0303), suites=4865-4866, no ext/groups/formats
        let expected_ja3_string = "771,4865-4866,,,";
        let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
        assert_eq!(hash, expected_hash);
    }

    #[test]
    fn compute_ja3_with_extensions_and_groups() {
        // Build supported_groups extension data: length(2) + groups
        let mut groups_ext = Vec::new();
        let groups: &[u16] = &[0x0017, 0x0018]; // secp256r1, secp384r1
        groups_ext.extend_from_slice(&((groups.len() * 2) as u16).to_be_bytes());
        for g in groups {
            groups_ext.extend_from_slice(&g.to_be_bytes());
        }

        // Build ec_point_formats extension data: length(1) + formats
        let mut formats_ext = Vec::new();
        let formats: &[u8] = &[0x00, 0x01]; // uncompressed, ansiX962_compressed_prime
        formats_ext.push(formats.len() as u8);
        formats_ext.extend_from_slice(formats);

        let extensions = vec![
            (0x0000_u16, vec![]),      // server_name (empty for test)
            (0x000a_u16, groups_ext),  // supported_groups
            (0x000b_u16, formats_ext), // ec_point_formats
            (0x0023_u16, vec![]),      // session_ticket
        ];

        let data = build_test_client_hello(0x0303, &[0x1301, 0xc02c], &extensions);
        let ja3 = compute_ja3(&data).expect("should parse");

        // JA3 string: "771,4865-49196,0-10-11-35,23-24,0-1"
        let expected_ja3_string = "771,4865-49196,0-10-11-35,23-24,0-1";
        let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
        assert_eq!(ja3, expected_hash);
    }

    #[test]
    fn compute_ja3_filters_grease_from_all_fields() {
        // Build supported_groups with a GREASE value mixed in
        let mut groups_ext = Vec::new();
        let groups: &[u16] = &[0x2a2a, 0x0017]; // GREASE + secp256r1
        groups_ext.extend_from_slice(&((groups.len() * 2) as u16).to_be_bytes());
        for g in groups {
            groups_ext.extend_from_slice(&g.to_be_bytes());
        }

        let extensions = vec![
            (0x0a0a_u16, vec![]),     // GREASE extension (should be filtered)
            (0x000a_u16, groups_ext), // supported_groups
        ];

        // Include a GREASE cipher suite
        let data = build_test_client_hello(0x0303, &[0x1a1a, 0x1301], &extensions);
        let ja3 = compute_ja3(&data).expect("should parse");

        // GREASE values should all be filtered:
        // version=771, suites=4865 (0x1a1a filtered), ext=10 (0x0a0a filtered),
        // groups=23 (0x2a2a filtered), formats=
        let expected_ja3_string = "771,4865,10,23,";
        let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
        assert_eq!(ja3, expected_hash);
    }

    #[test]
    fn compute_ja3_returns_none_for_invalid_data() {
        assert!(compute_ja3(&[]).is_none());
        assert!(compute_ja3(&[0x17, 0x03, 0x01]).is_none()); // wrong content type
        assert!(compute_ja3(&[0x16]).is_none()); // truncated
    }

    #[test]
    fn recording_stream_captures_writes() {
        let mut inner = Vec::new();
        {
            let mut recording = RecordingStream::new(&mut inner);
            recording.write_all(b"hello ").unwrap();
            recording.write_all(b"world").unwrap();
            let (_, recorded) = recording.into_parts();
            assert_eq!(recorded, b"hello world");
        }
        assert_eq!(inner, b"hello world");
    }

    #[test]
    fn recording_stream_delegates_reads() {
        let data: &[u8] = b"test data";
        let mut recording = RecordingStream::new(data);
        let mut buf = [0u8; 9];
        let n = recording.read(&mut buf).unwrap();
        assert_eq!(n, 9);
        assert_eq!(&buf, b"test data");
        // Reads should not be recorded
        assert!(recording.recorded_writes().is_empty());
    }
}
