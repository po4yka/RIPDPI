# Refactored Code Test Coverage Improvement Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add direct unit tests for functions that currently lack coverage after recent refactors (crypto consolidation, nom parser migration, BoringSSL feature).

**Architecture:** Pure unit tests targeting private/pub(crate) helpers that are only tested indirectly through integration paths. Each task is one test file addition or test block expansion.

**Tech Stack:** Rust `#[cfg(test)]`, `ring` crypto fixtures, `crypto_box` for DNSCrypt, existing `rust_packet_seeds` helpers.

---

### Task 1: QUIC Varint Codec Unit Tests

**Files:**
- Modify: `native/rust/crates/ripdpi-packets/src/quic.rs` (append to `mod tests`)

**Step 1: Write failing tests**

Append inside `mod tests { ... }` at end of file:

```rust
    // ---- QUIC varint codec unit tests ----

    #[test]
    fn read_quic_varint_decodes_1_byte_value() {
        // 0x25 = 0b00_100101, prefix 00 -> 1-byte, value = 37
        assert_eq!(read_quic_varint(&[0x25], 0), Some((37, 1)));
    }

    #[test]
    fn read_quic_varint_decodes_2_byte_value() {
        // 0x7bbd = 0b01_111011_10111101, prefix 01 -> 2-byte, value = 15293
        assert_eq!(read_quic_varint(&[0x7b, 0xbd], 0), Some((15293, 2)));
    }

    #[test]
    fn read_quic_varint_decodes_4_byte_value() {
        // 0x9d7f3e7d, prefix 10 -> 4-byte, value = 494878333
        assert_eq!(read_quic_varint(&[0x9d, 0x7f, 0x3e, 0x7d], 0), Some((494878333, 4)));
    }

    #[test]
    fn read_quic_varint_decodes_8_byte_value() {
        // 0xc2197c5eff14e88c, prefix 11 -> 8-byte, value = 151288809941952652
        assert_eq!(
            read_quic_varint(&[0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c], 0),
            Some((151288809941952652, 8))
        );
    }

    #[test]
    fn read_quic_varint_respects_offset() {
        assert_eq!(read_quic_varint(&[0xff, 0x25], 1), Some((37, 1)));
    }

    #[test]
    fn read_quic_varint_returns_none_for_empty_slice() {
        assert_eq!(read_quic_varint(&[], 0), None);
    }

    #[test]
    fn read_quic_varint_returns_none_for_truncated_2_byte() {
        // 0x40 -> prefix 01 -> needs 2 bytes, but only 1 available
        assert_eq!(read_quic_varint(&[0x40], 0), None);
    }

    #[test]
    fn read_quic_varint_returns_none_for_offset_beyond_slice() {
        assert_eq!(read_quic_varint(&[0x25], 5), None);
    }

    #[test]
    fn encode_quic_varint_1_byte_boundaries() {
        assert_eq!(encode_quic_varint(0), vec![0x00]);
        assert_eq!(encode_quic_varint(63), vec![0x3f]);
    }

    #[test]
    fn encode_quic_varint_2_byte_boundaries() {
        assert_eq!(encode_quic_varint(64), vec![0x40, 0x40]);
        assert_eq!(encode_quic_varint(16383), vec![0x7f, 0xff]);
    }

    #[test]
    fn encode_quic_varint_4_byte_boundaries() {
        assert_eq!(encode_quic_varint(16384), vec![0x80, 0x00, 0x40, 0x00]);
        assert_eq!(encode_quic_varint(1_073_741_823), vec![0xbf, 0xff, 0xff, 0xff]);
    }

    #[test]
    fn encode_quic_varint_8_byte() {
        let encoded = encode_quic_varint(1_073_741_824);
        assert_eq!(encoded.len(), 8);
        assert_eq!(encoded[0] & 0xc0, 0xc0); // 8-byte prefix
    }

    #[test]
    fn quic_varint_round_trips() {
        for value in [0, 1, 63, 64, 16383, 16384, 1_073_741_823, 1_073_741_824, u64::MAX >> 2] {
            let encoded = encode_quic_varint(value);
            let (decoded, len) = read_quic_varint(&encoded, 0).expect("round-trip decode");
            assert_eq!(decoded, value, "round-trip failed for {value}");
            assert_eq!(len, encoded.len());
        }
    }
```

**Step 2: Run tests**

Run: `cargo test -p ripdpi-packets -- quic_varint --nocapture`
Expected: All PASS

**Step 3: Commit**

```
test: add direct unit tests for QUIC varint codec
```

---

### Task 2: QUIC Crypto Frame Defragmentation Tests

**Files:**
- Modify: `native/rust/crates/ripdpi-packets/src/quic.rs` (append to `mod tests`)

**Step 1: Write tests**

Append inside `mod tests`:

```rust
    // ---- QUIC crypto frame defragmentation tests ----

    fn make_crypto_frame(offset: u64, data: &[u8]) -> Vec<u8> {
        let mut frame = Vec::new();
        append_quic_crypto_frame(&mut frame, offset, data);
        frame
    }

    #[test]
    fn defrag_single_crypto_frame() {
        let frame = make_crypto_frame(0, b"hello");
        let (data, complete) = defrag_quic_crypto_frames(&frame).expect("single frame");
        assert!(complete);
        assert_eq!(data, b"hello");
    }

    #[test]
    fn defrag_two_contiguous_frames() {
        let mut payload = make_crypto_frame(0, b"hel");
        payload.extend(make_crypto_frame(3, b"lo"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("two frames");
        assert!(complete);
        assert_eq!(data, b"hello");
    }

    #[test]
    fn defrag_frames_with_gap_reports_incomplete() {
        let mut payload = make_crypto_frame(0, b"AB");
        payload.extend(make_crypto_frame(4, b"EF"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("gap");
        assert!(!complete);
        assert_eq!(data.len(), 6);
        assert_eq!(&data[0..2], b"AB");
        assert_eq!(&data[4..6], b"EF");
    }

    #[test]
    fn defrag_skips_padding_frames() {
        let mut payload = vec![0x00, 0x00, 0x01]; // padding + ping
        payload.extend(make_crypto_frame(0, b"data"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("with padding");
        assert!(complete);
        assert_eq!(data, b"data");
    }

    #[test]
    fn defrag_rejects_empty_payload() {
        assert!(defrag_quic_crypto_frames(&[]).is_none());
    }

    #[test]
    fn defrag_rejects_only_padding() {
        assert!(defrag_quic_crypto_frames(&[0x00, 0x00, 0x01]).is_none());
    }

    #[test]
    fn defrag_rejects_unknown_frame_type() {
        let mut payload = make_crypto_frame(0, b"ok");
        payload.push(0x42); // unknown frame type
        assert!(defrag_quic_crypto_frames(&payload).is_none());
    }

    #[test]
    fn defrag_rejects_oversized_crypto_offset() {
        // QUIC_MAX_CRYPTO_LEN is 65536; create a frame that ends beyond it
        let frame = make_crypto_frame(65530, &[0u8; 10]);
        assert!(defrag_quic_crypto_frames(&frame).is_none());
    }
```

**Step 2: Run tests**

Run: `cargo test -p ripdpi-packets -- defrag --nocapture`
Expected: All PASS

**Step 3: Commit**

```
test: add unit tests for QUIC crypto frame defragmentation
```

---

### Task 3: TLS Extension Removal and KeyShare Group Removal Tests

**Files:**
- Modify: `native/rust/crates/ripdpi-packets/src/tls.rs` (append to `mod tests`)

**Step 1: Write tests**

Need to build a minimal TLS ClientHello-like buffer with known extensions.
Append inside `mod tests`:

```rust
    // ---- TLS extension manipulation unit tests ----

    /// Build a minimal buffer that looks like a TLS record with an extension list.
    /// Layout: [record header 5B][handshake header 4B][... skip_bytes ...][ext_list_len 2B][extensions...]
    /// The `skip` parameter passed to find_tls_ext_offset should be the offset of ext_list_len.
    fn build_ext_buffer(extensions: &[(u16, &[u8])]) -> (Vec<u8>, usize) {
        // Calculate extensions total size
        let ext_data_len: usize = extensions.iter().map(|(_, data)| 4 + data.len()).sum();

        // Record header (5 bytes): content_type=0x16, version=0x0303, record_length
        // Handshake header (4 bytes): type=0x01, handshake_length (3 bytes)
        // Minimal ClientHello fields before extensions: 2 (version) + 32 (random) + 1 (session_id_len) = 35
        let handshake_len = 35 + 2 + ext_data_len; // 35 fixed + 2 ext_list_len + ext data
        let record_len = handshake_len + 4; // + handshake header

        let mut buf = Vec::with_capacity(5 + record_len);
        // Record header
        buf.push(0x16); // content type: handshake
        buf.extend_from_slice(&0x0303u16.to_be_bytes()); // TLS 1.2
        buf.extend_from_slice(&(record_len as u16).to_be_bytes());
        // Handshake header
        buf.push(0x01); // ClientHello
        buf.push(0x00);
        buf.extend_from_slice(&(handshake_len as u16).to_be_bytes());
        // ClientHello fields (35 bytes of filler)
        buf.extend_from_slice(&[0u8; 35]);

        let ext_list_offset = buf.len();
        // Extension list length
        buf.extend_from_slice(&(ext_data_len as u16).to_be_bytes());
        // Extensions
        for (kind, data) in extensions {
            buf.extend_from_slice(&kind.to_be_bytes());
            buf.extend_from_slice(&(data.len() as u16).to_be_bytes());
            buf.extend_from_slice(data);
        }

        (buf, ext_list_offset)
    }

    #[test]
    fn remove_tls_ext_removes_known_extension() {
        let (mut buf, skip) = build_ext_buffer(&[
            (0x0000, b"sni-data"),     // SNI
            (0x0010, b"alpn-data"),    // ALPN
        ]);
        let n = buf.len();
        let removed = remove_tls_ext(&mut buf, n, skip, 0x0010); // remove ALPN
        assert_eq!(removed, 4 + 9); // header(4) + "alpn-data"(9)
    }

    #[test]
    fn remove_tls_ext_returns_zero_for_absent_extension() {
        let (mut buf, skip) = build_ext_buffer(&[(0x0000, b"sni")]);
        let n = buf.len();
        assert_eq!(remove_tls_ext(&mut buf, n, skip, 0xffff), 0);
    }

    #[test]
    fn remove_ks_group_removes_matching_group() {
        // Build a key_share extension (0x0033) with two groups
        // Key share format: [groups_list_len 2B] [group_id 2B][key_len 2B][key_data...]...
        let group_x25519: u16 = 0x001d;
        let group_kyber: u16 = 0x11ec;
        let key_x25519 = [0xAA; 32];
        let key_kyber = [0xBB; 64];

        let mut ks_data = Vec::new();
        let groups_len = (2 + 2 + key_x25519.len()) + (2 + 2 + key_kyber.len());
        ks_data.extend_from_slice(&(groups_len as u16).to_be_bytes());
        // Group 1: x25519
        ks_data.extend_from_slice(&group_x25519.to_be_bytes());
        ks_data.extend_from_slice(&(key_x25519.len() as u16).to_be_bytes());
        ks_data.extend_from_slice(&key_x25519);
        // Group 2: kyber
        ks_data.extend_from_slice(&group_kyber.to_be_bytes());
        ks_data.extend_from_slice(&(key_kyber.len() as u16).to_be_bytes());
        ks_data.extend_from_slice(&key_kyber);

        let (mut buf, skip) = build_ext_buffer(&[(0x0033, &ks_data)]);
        let n = buf.len();
        let removed = remove_ks_group(&mut buf, n, skip, group_kyber);
        assert_eq!(removed, 4 + key_kyber.len()); // group_header(4) + key_data(64)
    }

    #[test]
    fn remove_ks_group_returns_zero_for_absent_group() {
        let mut ks_data = Vec::new();
        let key = [0xAA; 32];
        ks_data.extend_from_slice(&(36u16).to_be_bytes()); // groups_list_len = 2+2+32
        ks_data.extend_from_slice(&0x001du16.to_be_bytes()); // x25519
        ks_data.extend_from_slice(&(key.len() as u16).to_be_bytes());
        ks_data.extend_from_slice(&key);

        let (mut buf, skip) = build_ext_buffer(&[(0x0033, &ks_data)]);
        let n = buf.len();
        assert_eq!(remove_ks_group(&mut buf, n, skip, 0x11ec), 0); // kyber not present
    }

    #[test]
    fn remove_ks_group_returns_zero_without_key_share_ext() {
        let (mut buf, skip) = build_ext_buffer(&[(0x0000, b"sni")]); // no key_share
        let n = buf.len();
        assert_eq!(remove_ks_group(&mut buf, n, skip, 0x001d), 0);
    }
```

**Step 2: Run tests**

Run: `cargo test -p ripdpi-packets -- remove_tls_ext --nocapture && cargo test -p ripdpi-packets -- remove_ks_group --nocapture`
Expected: All PASS

**Step 3: Commit**

```
test: add unit tests for TLS extension and KeyShare group removal
```

---

### Task 4: TLS resize_sni and resize_ech_ext Tests

**Files:**
- Modify: `native/rust/crates/ripdpi-packets/src/tls.rs` (append to `mod tests`)

**Step 1: Write tests**

```rust
    #[test]
    fn resize_sni_grows_extension() {
        // SNI extension: [type 2B][ext_len 2B][list_len 2B][entry_type 1B][name_len 2B][name]
        // "ab" = 2 bytes -> sni_size = 2 + 5 = 7 (ext data includes the 5-byte wrapper)
        let sni_name = b"ab";
        let sni_size = sni_name.len() + 5; // name + wrapper overhead
        let (mut buf, skip) = build_ext_buffer(&[(0x0000, &{
            let mut d = Vec::new();
            d.extend_from_slice(&((sni_name.len() + 3) as u16).to_be_bytes()); // list len
            d.push(0x00); // host_name type
            d.extend_from_slice(&(sni_name.len() as u16).to_be_bytes()); // name len
            d.extend_from_slice(sni_name);
            d
        })]);
        // Add tail padding so buffer has room to grow
        buf.extend_from_slice(&[0u8; 20]);
        let n = buf.len() - 20; // actual data len
        let sni_offs = find_tls_ext_offset(0x0000, &buf[..n], skip).unwrap();
        let result = resize_sni(&mut buf, n, sni_offs, sni_size, 10); // grow to 10-char name
        assert!(result);
    }

    #[test]
    fn resize_sni_shrinks_extension() {
        let sni_name = b"longexample.com";
        let sni_size = sni_name.len() + 5;
        let (mut buf, skip) = build_ext_buffer(&[(0x0000, &{
            let mut d = Vec::new();
            d.extend_from_slice(&((sni_name.len() + 3) as u16).to_be_bytes());
            d.push(0x00);
            d.extend_from_slice(&(sni_name.len() as u16).to_be_bytes());
            d.extend_from_slice(sni_name);
            d
        })]);
        let n = buf.len();
        let sni_offs = find_tls_ext_offset(0x0000, &buf[..n], skip).unwrap();
        let result = resize_sni(&mut buf, n, sni_offs, sni_size, 3); // shrink to 3-char name
        assert!(result);
    }

    #[test]
    fn resize_sni_rejects_overflow() {
        let sni_name = b"a";
        let sni_size = sni_name.len() + 5;
        let (mut buf, skip) = build_ext_buffer(&[(0x0000, &{
            let mut d = Vec::new();
            d.extend_from_slice(&((sni_name.len() + 3) as u16).to_be_bytes());
            d.push(0x00);
            d.extend_from_slice(&(sni_name.len() as u16).to_be_bytes());
            d.extend_from_slice(sni_name);
            d
        })]);
        let n = buf.len();
        let sni_offs = find_tls_ext_offset(0x0000, &buf[..n], skip).unwrap();
        // Try to grow way beyond buffer capacity
        let result = resize_sni(&mut buf, n, sni_offs, sni_size, 50000);
        assert!(!result);
    }

    #[test]
    fn resize_ech_ext_returns_zero_when_absent() {
        let (mut buf, skip) = build_ext_buffer(&[(0x0000, b"sni")]); // no ECH
        let n = buf.len();
        assert_eq!(resize_ech_ext(&mut buf, n, skip, 10), 0);
    }
```

**Step 2: Run tests**

Run: `cargo test -p ripdpi-packets -- resize_sni --nocapture && cargo test -p ripdpi-packets -- resize_ech --nocapture`
Expected: All PASS

**Step 3: Commit**

```
test: add unit tests for TLS SNI and ECH extension resizing
```

---

### Task 5: DNSCrypt Provider Name and Response Decryption Tests

**Files:**
- Modify: `native/rust/crates/ripdpi-dns-resolver/src/dnscrypt.rs` (append to `mod tests`)

**Step 1: Write tests**

```rust
    // ---- dnscrypt_provider_name tests ----

    #[test]
    fn provider_name_extracts_trimmed_name() {
        let endpoint = make_endpoint(None);
        let mut ep = endpoint;
        ep.dnscrypt_provider_name = Some("  2.dnscrypt-cert.example.com  ".to_string());
        assert_eq!(dnscrypt_provider_name(&ep).unwrap(), "2.dnscrypt-cert.example.com");
    }

    #[test]
    fn provider_name_rejects_none() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = None;
        assert!(dnscrypt_provider_name(&ep).is_err());
    }

    #[test]
    fn provider_name_rejects_empty_string() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = Some("".to_string());
        assert!(dnscrypt_provider_name(&ep).is_err());
    }

    #[test]
    fn provider_name_rejects_whitespace_only() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = Some("   ".to_string());
        assert!(dnscrypt_provider_name(&ep).is_err());
    }

    // ---- decrypt_dnscrypt_response tests ----

    fn test_crypto_box() -> (ChaChaBox, [u8; 32], [u8; 32]) {
        use crypto_box::{PublicKey, SecretKey};
        let server_secret = SecretKey::from([1u8; 32]);
        let client_secret = SecretKey::from([2u8; 32]);
        let server_public = server_secret.public_key();
        let client_public = client_secret.public_key();
        // Client decrypts with: ChaChaBox(server_public, client_secret)
        let decrypt_box = ChaChaBox::new(&server_public, &client_secret);
        // Server encrypts with: ChaChaBox(client_public, server_secret)
        let _encrypt_box = ChaChaBox::new(&client_public, &server_secret);
        (decrypt_box, *server_secret.as_bytes(), *client_secret.as_bytes())
    }

    #[test]
    fn decrypt_response_rejects_too_short() {
        let (crypto_box, _, _) = test_crypto_box();
        let short = vec![0u8; 8 + DNSCRYPT_NONCE_SIZE]; // exactly at boundary
        let err = decrypt_dnscrypt_response(&crypto_box, &short, &[0u8; 12]).unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptDecrypt(_)));
    }

    #[test]
    fn decrypt_response_rejects_bad_magic() {
        let (crypto_box, _, _) = test_crypto_box();
        let mut response = vec![0u8; 100];
        response[..8].copy_from_slice(b"BADMAGIC");
        let err = decrypt_dnscrypt_response(&crypto_box, &response, &[0u8; 12]).unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptDecrypt(_)));
    }

    #[test]
    fn decrypt_response_rejects_nonce_prefix_mismatch() {
        let (crypto_box, _, _) = test_crypto_box();
        let mut response = vec![0u8; 100];
        response[..8].copy_from_slice(&DNSCRYPT_RESPONSE_MAGIC);
        // Nonce at [8..32], first 12 bytes are prefix
        response[8..20].copy_from_slice(&[0xAA; 12]);
        let expected_prefix = [0xBB; 12]; // mismatch
        let err = decrypt_dnscrypt_response(&crypto_box, &response, &expected_prefix).unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptDecrypt(_)));
    }
```

**Step 2: Run tests**

Run: `cargo test -p ripdpi-dns-resolver -- provider_name --nocapture && cargo test -p ripdpi-dns-resolver -- decrypt_response --nocapture`
Expected: All PASS

**Step 3: Commit**

```
test: add unit tests for DNSCrypt provider name extraction and response decryption
```

---

### Task 6: Autolearn Host Normalization and Penalty Check Tests

**Files:**
- Modify: `native/rust/crates/ripdpi-runtime/src/runtime_policy/autolearn.rs` (append to `mod tests`)

**Step 1: Write tests**

```rust
    // ---- normalize_learned_host tests ----

    #[test]
    fn normalize_host_lowercases_and_trims() {
        assert_eq!(normalize_learned_host("  Example.COM  "), Some("example.com".to_string()));
    }

    #[test]
    fn normalize_host_strips_trailing_dots() {
        assert_eq!(normalize_learned_host("example.com."), Some("example.com".to_string()));
        assert_eq!(normalize_learned_host("example.com..."), Some("example.com".to_string()));
    }

    #[test]
    fn normalize_host_rejects_empty() {
        assert_eq!(normalize_learned_host(""), None);
        assert_eq!(normalize_learned_host("   "), None);
    }

    #[test]
    fn normalize_host_rejects_ipv4() {
        assert_eq!(normalize_learned_host("192.168.1.1"), None);
        assert_eq!(normalize_learned_host("127.0.0.1"), None);
    }

    #[test]
    fn normalize_host_rejects_ipv6() {
        assert_eq!(normalize_learned_host("::1"), None);
        assert_eq!(normalize_learned_host("fe80::1"), None);
    }

    #[test]
    fn normalize_host_accepts_domain_with_dots_only_trailing() {
        assert_eq!(normalize_learned_host("."), None); // only dot, trimmed to empty
    }

    // ---- host_has_active_penalty tests ----

    #[test]
    fn penalty_active_when_expiry_in_future() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, GroupStats { penalty_until_ms: 1000, ..Default::default() });
        assert!(host_has_active_penalty(&record, 500));
    }

    #[test]
    fn penalty_expired_when_expiry_equals_now() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, GroupStats { penalty_until_ms: 1000, ..Default::default() });
        assert!(!host_has_active_penalty(&record, 1000));
    }

    #[test]
    fn penalty_expired_when_expiry_in_past() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, GroupStats { penalty_until_ms: 500, ..Default::default() });
        assert!(!host_has_active_penalty(&record, 1000));
    }

    #[test]
    fn no_penalty_when_group_stats_empty() {
        let record = LearnedHostRecord::default();
        assert!(!host_has_active_penalty(&record, 1000));
    }

    #[test]
    fn penalty_active_when_any_group_has_future_expiry() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, GroupStats { penalty_until_ms: 100, ..Default::default() });
        record.group_stats.insert(1, GroupStats { penalty_until_ms: 2000, ..Default::default() });
        assert!(host_has_active_penalty(&record, 500));
    }
```

**Step 2: Run tests**

Run: `cargo test -p ripdpi-runtime -- normalize_host --nocapture && cargo test -p ripdpi-runtime -- penalty --nocapture`
Expected: All PASS

**Step 3: Commit**

```
test: add unit tests for autolearn host normalization and penalty checks
```
