use super::*;

#[allow(dead_code)]
mod rust_packet_seeds {
    use crate as ripdpi_packets;

    include!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/rust_packet_seeds.rs"));
}

#[test]
fn parse_quic_initial_extracts_v1_sni() {
    let packet =
        build_realistic_quic_initial(QUIC_V1_VERSION, Some("docs.example.test")).expect("build quic initial v1");
    let parsed = parse_quic_initial(&packet).expect("parse quic initial v1");

    assert!(is_quic_initial(&packet));
    assert_eq!(parsed.version, QUIC_V1_VERSION);
    assert!(parsed.is_crypto_complete);
    assert_eq!(parsed.host(), b"docs.example.test");
}

#[test]
fn parse_quic_initial_extracts_v2_sni() {
    let packet =
        build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("build quic initial v2");
    let parsed = parse_quic_initial(&packet).expect("parse quic initial v2");

    assert!(is_quic_initial(&packet));
    assert_eq!(parsed.version, QUIC_V2_VERSION);
    assert!(parsed.is_crypto_complete);
    assert_eq!(parsed.host(), b"media.example.test");
}

#[test]
fn realistic_quic_fake_builder_round_trips_and_uses_default_tls_base() {
    let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build default realistic fake");
    let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

    assert_eq!(parsed.version, QUIC_V1_VERSION);
    assert_eq!(parsed.host(), b"www.google.com");
    assert_eq!(packet.len(), QUIC_FAKE_INITIAL_TARGET_LEN);
}

#[test]
fn realistic_quic_fake_builder_applies_host_override() {
    let packet =
        build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build realistic fake");
    let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

    assert_eq!(parsed.host(), b"video.example.test");
}

#[test]
fn realistic_quic_fake_builder_defaults_to_v1_for_unknown_versions() {
    let packet = build_realistic_quic_initial(0xface_feed, Some("video.example.test")).expect("build realistic fake");
    let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

    assert_eq!(parsed.version, QUIC_V1_VERSION);
    assert_eq!(parsed.host(), b"video.example.test");
}

#[test]
fn compat_default_quic_fake_matches_fixed_compatibility_layout() {
    let packet = default_fake_quic_compat();

    assert_eq!(packet.len(), DEFAULT_FAKE_QUIC_COMPAT_LEN);
    assert_eq!(packet[0], 0x40);
    assert!(packet[1..].iter().all(|byte| *byte == 0));
}

#[test]
fn parse_quic_initial_rejects_unsupported_versions() {
    let mut packet = rust_packet_seeds::quic_initial_v1();
    packet[1..5].copy_from_slice(&0x0000_0002u32.to_be_bytes());

    assert!(!is_quic_initial(&packet));
    assert!(parse_quic_initial(&packet).is_none());
}

#[test]
fn parse_quic_initial_rejects_bad_tags() {
    let mut packet = rust_packet_seeds::quic_initial_v1();
    let last = packet.len() - 1;
    packet[last] ^= 0xff;

    assert!(parse_quic_initial(&packet).is_none());
}

#[test]
fn parse_quic_initial_rejects_truncated_packets() {
    let mut packet = rust_packet_seeds::quic_initial_v1();
    packet.truncate(packet.len() - 32);

    assert!(parse_quic_initial(&packet).is_none());
}

#[test]
fn parse_quic_initial_rejects_incomplete_crypto_frames() {
    let packet = rust_packet_seeds::quic_initial_with_crypto_gap(QUIC_V1_VERSION, "docs.example.test");

    assert!(parse_quic_initial(&packet).is_none());
}

#[test]
fn parse_quic_initial_rejects_missing_sni() {
    let packet = rust_packet_seeds::quic_initial_missing_sni(QUIC_V1_VERSION);

    assert!(parse_quic_initial(&packet).is_none());
}

// ---- Key derivation and label unit tests ----

#[test]
fn quic_hkdf_label_produces_correct_binary_format() {
    let label = quic_hkdf_label("tls13 quic key", 16).expect("label");
    // First 2 bytes: output length as u16 big-endian
    assert_eq!(&label[..2], &16u16.to_be_bytes());
    // Next byte: label length
    assert_eq!(label[2], 14); // "tls13 quic key".len()
    // Then the label bytes
    assert_eq!(&label[3..17], b"tls13 quic key");
    // Final byte: empty context (0 length)
    assert_eq!(label[17], 0);
    assert_eq!(label.len(), 18);
}

#[test]
fn quic_hkdf_label_rejects_oversized_output() {
    assert!(quic_hkdf_label("x", u16::MAX as usize + 1).is_none());
}

#[test]
fn quic_v1_initial_secret_is_deterministic() {
    let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    let s1 = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1 secret first");
    let s2 = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1 secret second");
    assert_eq!(s1, s2);
}

#[test]
fn quic_v1_and_v2_produce_different_secrets_for_same_dcid() {
    let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    let v1 = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1");
    let v2 = quic_derive_client_initial_secret(&dcid, QUIC_V2_VERSION).expect("v2");
    assert_ne!(v1, v2);
}

#[test]
fn quic_derive_rejects_unsupported_version() {
    let dcid = [0x83, 0x94, 0xc8, 0xf0];
    assert!(quic_derive_client_initial_secret(&dcid, 0xdead_beef).is_none());
}

#[test]
fn quic_expand_label_produces_correct_sizes() {
    let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    let secret = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("secret");

    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    quic_expand_label(&secret, "tls13 quic key", &mut key).expect("key");
    quic_expand_label(&secret, "tls13 quic iv", &mut iv).expect("iv");
    quic_expand_label(&secret, "tls13 quic hp", &mut hp).expect("hp");

    // key, iv, hp should all be non-zero (overwhelmingly unlikely to be all zeros)
    assert!(key.iter().any(|b| *b != 0), "key should not be all zeros");
    assert!(iv.iter().any(|b| *b != 0), "iv should not be all zeros");
    assert!(hp.iter().any(|b| *b != 0), "hp should not be all zeros");
    // key and hp are different despite same length
    assert_ne!(key, hp, "key and hp should differ");
}

#[test]
fn quic_v1_initial_secret_matches_rfc_9001_appendix_a() {
    // RFC 9001, Section A.1: Initial Keys
    // DCID = 0x8394c8f03e515708
    let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    let secret = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1 secret");

    // Expected client_initial_secret from RFC 9001, Appendix A.1:
    let expected = [
        0xc0, 0x0c, 0xf1, 0x51, 0xca, 0x5b, 0xe0, 0x75, 0xed, 0x0e, 0xbf, 0xb5, 0xc8, 0x03, 0x23, 0xc4, 0x2d, 0x6b,
        0x7d, 0xb6, 0x78, 0x81, 0x28, 0x9a, 0xf4, 0x00, 0x8f, 0x1f, 0x6c, 0x35, 0x7a, 0xea,
    ];
    assert_eq!(secret, expected, "client initial secret must match RFC 9001 Appendix A.1");
}

#[test]
fn quic_v2_uses_different_label_namespace() {
    let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    let v1_secret = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1");
    let v2_secret = quic_derive_client_initial_secret(&dcid, QUIC_V2_VERSION).expect("v2");

    let mut v1_key = [0u8; 16];
    let mut v2_key = [0u8; 16];
    quic_expand_label(&v1_secret, "tls13 quic key", &mut v1_key).expect("v1 key");
    quic_expand_label(&v2_secret, "tls13 quicv2 key", &mut v2_key).expect("v2 key");
    assert_ne!(v1_key, v2_key);
}

#[test]
fn different_dcids_produce_different_secrets() {
    let dcid_a = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08];
    let dcid_b = [0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18];
    let sa = quic_derive_client_initial_secret(&dcid_a, QUIC_V1_VERSION).expect("a");
    let sb = quic_derive_client_initial_secret(&dcid_b, QUIC_V1_VERSION).expect("b");
    assert_ne!(sa, sb);
}

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
    assert_eq!(read_quic_varint(&[0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c], 0), Some((151288809941952652, 8)));
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
    assert_eq!(encoded[0] & 0xc0, 0xc0);
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
    let mut payload = vec![0x00, 0x00, 0x01];
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
    payload.push(0x42);
    assert!(defrag_quic_crypto_frames(&payload).is_none());
}

#[test]
fn defrag_rejects_oversized_crypto_offset() {
    let frame = make_crypto_frame(65530, &[0u8; 10]);
    assert!(defrag_quic_crypto_frames(&frame).is_none());
}

// ---- DPI evasion tamper function tests ----

#[test]
fn tamper_quic_version_replaces_version_field() {
    let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build packet");
    let tampered = tamper_quic_version(&packet, 0x1a2a3a4a).expect("tamper version");

    assert_eq!(&tampered[1..5], &[0x1a, 0x2a, 0x3a, 0x4a]);
    // DPI cannot decrypt with the wrong version salt
    assert!(parse_quic_initial(&tampered).is_none());
}

#[test]
fn tamper_quic_initial_split_sni_produces_valid_packet() {
    let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build packet");
    let original = parse_quic_initial(&packet).expect("parse original");
    let split_offset = original.tls_info.host_start;

    let tampered = tamper_quic_initial_split_sni(&packet, split_offset).expect("tamper split");
    let reparsed = parse_quic_initial(&tampered).expect("parse tampered");

    assert_eq!(reparsed.client_hello, original.client_hello);
}

#[test]
fn parse_quic_initial_seed_round_trips_original_header_material() {
    let packet = build_realistic_quic_initial(QUIC_V2_VERSION, Some("seed.example.test")).expect("build packet");
    let seed = parse_quic_initial_seed(&packet).expect("parse seed");
    let reparsed = parse_quic_initial(&packet).expect("parse original");

    assert_eq!(seed.version, QUIC_V2_VERSION);
    assert_eq!(seed.dcid, QUIC_FAKE_DCID);
    assert_eq!(seed.scid, QUIC_FAKE_SCID);
    assert!(seed.token.is_empty());
    assert!(is_tls_client_hello(&seed.client_hello));
    assert_eq!(&seed.client_hello[TLS_RECORD_HEADER_LEN..], reparsed.client_hello.as_slice());
}

#[test]
fn browser_like_quic_initial_supports_firefox_profile() {
    let packet = build_browser_like_quic_initial(
        QUIC_V1_VERSION,
        Some("firefox.example.test"),
        QuicInitialBrowserProfile::FirefoxAndroid,
    )
    .expect("build firefox-like packet");
    let parsed = parse_quic_initial(&packet).expect("parse firefox-like packet");

    assert_eq!(parsed.version, QUIC_V1_VERSION);
    assert_eq!(parsed.host(), b"firefox.example.test");
}

#[test]
fn browser_like_quic_initial_wire_images_are_stable() {
    let cases = [
        (
            "chrome120",
            build_browser_like_quic_initial(
                QUIC_V1_VERSION,
                Some("fixture.example.test"),
                QuicInitialBrowserProfile::ChromeAndroid,
            )
            .expect("build chrome-like packet"),
            9_608_150_740_566_105_141,
        ),
        (
            "firefox121",
            build_browser_like_quic_initial(
                QUIC_V1_VERSION,
                Some("fixture.example.test"),
                QuicInitialBrowserProfile::FirefoxAndroid,
            )
            .expect("build firefox-like packet"),
            11_472_675_710_253_673_189,
        ),
        (
            "generic_v1",
            build_generic_quic_initial(QUIC_V1_VERSION, Some("fixture.example.test")).expect("build generic packet"),
            2_193_023_961_024_112_642,
        ),
    ];

    let fingerprints: Vec<_> = cases.iter().map(|(_, packet, _)| stable_wire_fingerprint(packet)).collect();
    assert_ne!(fingerprints[0], fingerprints[1]);
    assert_ne!(fingerprints[0], fingerprints[2]);
    assert_ne!(fingerprints[1], fingerprints[2]);

    for (name, packet, expected_fingerprint) in cases {
        let parsed = parse_quic_initial(&packet).unwrap_or_else(|| panic!("parse {name} packet"));
        assert_eq!(parsed.version, QUIC_V1_VERSION, "{name}");
        assert_eq!(parsed.host(), b"fixture.example.test", "{name}");
        assert_eq!(packet.len(), QUIC_FAKE_INITIAL_TARGET_LEN, "{name}");
        assert_eq!(stable_wire_fingerprint(&packet), expected_fingerprint, "{name}");
    }
}

#[test]
fn packetize_quic_initial_split_layout_rewrites_crypto_frame_boundaries() {
    let packet = build_realistic_quic_initial(QUIC_V2_VERSION, Some("layout.example.test")).expect("build packet");
    let seed = parse_quic_initial_seed(&packet).expect("seed");
    let split_offset = parse_quic_initial(&packet).expect("parse").tls_info.host_start;
    let packetized = packetize_quic_initial(&seed, &QuicInitialPacketLayout::split_at(split_offset, packet.len()))
        .expect("packetize split layout");
    let layout = parse_quic_initial_layout(&packetized).expect("parse packetized layout");

    assert_eq!(layout.info.host(), b"layout.example.test");
    assert_eq!(layout.crypto_frames.len(), 2);
    assert_eq!(layout.crypto_frames[0].crypto_offset + layout.crypto_frames[0].data_len, split_offset);
    assert_eq!(layout.crypto_frames[1].crypto_offset, split_offset);
}

#[test]
fn packetize_quic_initial_respects_padding_target() {
    let seed = build_browser_like_quic_initial_seed(
        QUIC_V2_VERSION,
        Some("padding.example.test"),
        QuicInitialBrowserProfile::ChromeAndroid,
    )
    .expect("seed");
    let mut layout = QuicInitialPacketLayout::contiguous(1408);
    layout.extra_tail_padding = 32;
    let packetized = packetize_quic_initial(&seed, &layout).expect("packetize");
    let reparsed = parse_quic_initial(&packetized).expect("parse packetized");

    assert!(packetized.len() >= 1408);
    assert_eq!(reparsed.version, QUIC_V2_VERSION);
    assert_eq!(reparsed.host(), b"padding.example.test");
}

#[test]
fn packetize_quic_initial_packet_number_changes_wire_image() {
    let seed = build_browser_like_quic_initial_seed(
        QUIC_V2_VERSION,
        Some("pn.example.test"),
        QuicInitialBrowserProfile::ChromeAndroid,
    )
    .expect("seed");
    let baseline = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("baseline");
    let mut with_gap = QuicInitialPacketLayout::contiguous(1200);
    with_gap.packet_number = 2;
    let gapped = packetize_quic_initial(&seed, &with_gap).expect("gapped");

    assert_ne!(baseline, gapped);
    assert_eq!(&gapped[1..5], &QUIC_V2_VERSION.to_be_bytes());
}

/// A nonzero packet number must produce a spec-compliant nonce
/// (IV XOR pn). Otherwise every pn under one DCID-derived key set reuses the
/// same AES-GCM nonce and receivers computing IV^pn cannot decrypt at all.
#[test]
fn packetize_quic_initial_nonzero_packet_number_roundtrips() {
    let seed = build_browser_like_quic_initial_seed(
        QUIC_V1_VERSION,
        Some("pnr.example.test"),
        QuicInitialBrowserProfile::ChromeAndroid,
    )
    .expect("seed");

    for packet_number in [1u32, 2, 7, 0xffff_ffff] {
        let layout = QuicInitialPacketLayout {
            crypto_frame_offsets: Vec::new(),
            min_datagram_len: 1200,
            extra_tail_padding: 0,
            packet_number,
        };
        let packetized =
            packetize_quic_initial(&seed, &layout).unwrap_or_else(|| panic!("packetize pn={packet_number}"));
        let reparsed =
            parse_quic_initial(&packetized).unwrap_or_else(|| panic!("pn={packet_number} must stay decryptable"));

        assert_eq!(reparsed.host(), b"pnr.example.test", "pn={packet_number}");
        assert_eq!(&reparsed.client_hello, &seed.client_hello[TLS_RECORD_HEADER_LEN..], "pn={packet_number}");
    }
}

/// Distinct packet numbers under one DCID must not reuse an AES-GCM nonce:
/// identical keystream across packets would leak payload relations to DPI.
#[test]
fn packetize_quic_initial_distinct_packet_numbers_use_distinct_ciphertexts() {
    let seed = build_browser_like_quic_initial_seed(QUIC_V1_VERSION, None, QuicInitialBrowserProfile::ChromeAndroid)
        .expect("seed");

    let ciphertext_for = |packet_number: u32| -> Vec<u8> {
        let layout = QuicInitialPacketLayout {
            crypto_frame_offsets: Vec::new(),
            min_datagram_len: 1200,
            extra_tail_padding: 0,
            packet_number,
        };
        let packet = packetize_quic_initial(&seed, &layout).expect("packetize");
        let pn_offset = parse_quic_initial_header(&packet).expect("header").pn_offset;
        // Ciphertext body only. The trailing AEAD tag also authenticates the AAD,
        // which carries the packet number, so the tag differs even under nonce
        // reuse and would mask an identical keystream.
        packet[pn_offset + 4..packet.len() - QUIC_TAG_LEN].to_vec()
    };

    assert_ne!(ciphertext_for(0), ciphertext_for(1));
    assert_ne!(ciphertext_for(1), ciphertext_for(2));
}

#[test]
fn tamper_quic_version_returns_none_for_short_header() {
    // Short header: bit 7 = 0
    let packet = vec![0x40, 0x01, 0x02, 0x03, 0x04, 0x05];
    assert!(tamper_quic_version(&packet, 0x1a2a3a4a).is_none());
}

// ---- Additional tamper edge case tests ----

#[test]
fn tamper_quic_version_returns_none_for_too_short() {
    assert!(tamper_quic_version(&[0xc0, 0x01, 0x02], 0xdead).is_none());
    assert!(tamper_quic_version(&[], 0xdead).is_none());
}

#[test]
fn tamper_quic_version_preserves_packet_length() {
    let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build");
    let tampered = tamper_quic_version(&packet, 0xffff_ffff).expect("tamper");
    assert_eq!(tampered.len(), packet.len());
}

#[test]
fn tamper_quic_initial_split_sni_rejects_zero_offset() {
    let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build");
    assert!(tamper_quic_initial_split_sni(&packet, 0).is_none());
}

#[test]
fn tamper_quic_initial_split_sni_rejects_offset_beyond_payload() {
    let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build");
    let parsed = parse_quic_initial(&packet).expect("parse");
    assert!(tamper_quic_initial_split_sni(&packet, parsed.client_hello.len()).is_none());
}

#[test]
fn tamper_quic_initial_split_sni_v2_round_trips() {
    let packet = build_realistic_quic_initial(QUIC_V2_VERSION, Some("test.example.org")).expect("build v2");
    let original = parse_quic_initial(&packet).expect("parse original");
    let split_offset = original.tls_info.host_start;

    let tampered = tamper_quic_initial_split_sni(&packet, split_offset).expect("tamper v2");
    let reparsed = parse_quic_initial(&tampered).expect("reparse v2");
    assert_eq!(reparsed.version, QUIC_V2_VERSION);
    assert_eq!(reparsed.client_hello, original.client_hello);
}

#[test]
fn build_quic_initial_from_tls_rejects_non_tls() {
    assert!(build_quic_initial_from_tls(QUIC_V1_VERSION, b"not tls", 0).is_none());
}

#[test]
fn build_quic_initial_from_tls_rejects_empty() {
    assert!(build_quic_initial_from_tls(QUIC_V1_VERSION, &[], 0).is_none());
}

#[test]
fn defrag_overlapping_frames_uses_last_write_wins() {
    // Two frames that overlap: [0..3] "ABC" and [1..4] "XYZ"
    let mut payload = make_crypto_frame(0, b"ABC");
    payload.extend(make_crypto_frame(1, b"XYZ"));
    let (data, complete) = defrag_quic_crypto_frames(&payload).expect("overlap");
    assert!(complete);
    assert_eq!(data.len(), 4);
    // Second frame overwrites bytes 1..4
    assert_eq!(&data[1..4], b"XYZ");
}

#[test]
fn parse_quic_initial_header_rejects_empty_dcid() {
    // Manually craft a packet with dcid_len=0
    let mut packet = vec![0xc3]; // Long header, Initial type
    packet.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    packet.push(0); // dcid_len = 0 (should be rejected)
    packet.resize(QUIC_INITIAL_MIN_LEN, 0);
    assert!(parse_quic_initial_header(&packet).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_oversized_dcid() {
    let mut packet = vec![0xc3];
    packet.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    packet.push(21); // dcid_len = 21 > QUIC_MAX_CID_LEN
    packet.resize(QUIC_INITIAL_MIN_LEN, 0);
    assert!(parse_quic_initial_header(&packet).is_none());
}

fn stable_wire_fingerprint(packet: &[u8]) -> u64 {
    packet.iter().fold(0xcbf2_9ce4_8422_2325, |acc, byte| (acc ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3))
}

// ---- parse_quic_initial_header negative / robustness tests ----
//
// Each test exercises a distinct truncation or malformation that an attacker
// could craft from a UDP payload. Every assertion checks that the parser
// returns None and never panics (no index-OOB, no unwrap crash).

/// Helper: build a minimally valid raw header buffer (not encrypted, just
/// enough to satisfy parse_quic_initial_header) so truncation tests can
/// slice it at exact field boundaries.
fn make_raw_initial_header(dcid_len: u8, scid_len: u8, token_len: u64, payload_len: u64) -> Vec<u8> {
    let mut buf = Vec::new();
    // First byte: Long-header (0x80) | fixed (0x40) | Initial type bits 0x03 → 0xc3
    buf.push(0xc3);
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes()); // bytes 1-4
    buf.push(dcid_len); // byte 5
    buf.extend(std::iter::repeat_n(0xab, dcid_len as usize)); // DCID
    buf.push(scid_len); // SCID len
    buf.extend(std::iter::repeat_n(0xcd, scid_len as usize)); // SCID
    buf.extend_from_slice(&encode_quic_varint(token_len)); // token length varint
    buf.extend(std::iter::repeat_n(0x00, token_len as usize)); // token bytes
    buf.extend_from_slice(&encode_quic_varint(payload_len)); // payload length varint
    // Payload bytes (all zeros — we only test the header parser, not decryption)
    buf.extend(std::iter::repeat_n(0x00, payload_len as usize));
    buf
}

#[test]
fn parse_quic_initial_header_rejects_empty_input() {
    assert!(parse_quic_initial_header(&[]).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_single_byte() {
    assert!(parse_quic_initial_header(&[0xc3]).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_short_header_flag() {
    // bit 7 clear → short header, must reject
    let mut buf = vec![0x43u8]; // 0x40 set, 0x80 clear
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.resize(QUIC_INITIAL_MIN_LEN, 0);
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_missing_fixed_bit() {
    // bit 6 (0x40) clear → invalid QUIC packet
    let mut buf = vec![0x80u8];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.resize(QUIC_INITIAL_MIN_LEN, 0);
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_below_min_len() {
    // A buffer of exactly QUIC_INITIAL_MIN_LEN - 1 bytes with correct flags
    let mut buf = vec![0xc3u8];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.resize(QUIC_INITIAL_MIN_LEN - 1, 0);
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_at_version_field() {
    // Only 4 bytes total (no room for the full version u32 at offset 1)
    let buf = [0xc3, 0x00, 0x00, 0x00]; // needs bytes 1..5 for version
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_after_dcid_len_byte() {
    // Provide exactly 6 bytes: first-byte + version(4) + dcid_len(1), but no DCID bytes
    let dcid_len = 8u8;
    let mut buf = vec![0xc3];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.push(dcid_len);
    // Missing dcid_len bytes of DCID — buffer ends here
    buf.resize(QUIC_INITIAL_MIN_LEN.min(buf.len()), 0);
    // Buffer is shorter than QUIC_INITIAL_MIN_LEN so min-len guard fires first,
    // but we also verify no panic for a truly tiny slice:
    assert!(parse_quic_initial_header(&[0xc3, 0x00, 0x00, 0x00, 0x01, 8]).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_after_dcid() {
    // Build a buffer that has valid first-byte/version/dcid_len/dcid but is
    // cut immediately before the SCID-len byte.
    let dcid_len = 8u8;
    let mut buf = vec![0xc3];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.push(dcid_len);
    buf.extend(std::iter::repeat_n(0xab, dcid_len as usize));
    // NO scid_len byte — pad to QUIC_INITIAL_MIN_LEN with non-zero to make the
    // parser reach the scid_len position but then fail on scid data
    buf.resize(QUIC_INITIAL_MIN_LEN, 0xff);
    // The scid_len at the current position will be 0xff = 255 > QUIC_MAX_CID_LEN(20),
    // so the parser must reject it without panicking.
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_after_scid_len() {
    // scid_len is valid (4) but not enough bytes for the SCID follow
    let full = make_raw_initial_header(8, 4, 0, 20);
    // Truncate to just before the SCID data ends
    let scid_data_end = 1 + 4 + 1 + 8 + 1 + 2; // approximate, cuts inside SCID
    let truncated = &full[..scid_data_end.min(full.len())];
    assert!(parse_quic_initial_header(truncated).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_at_token_varint() {
    // Build header with 0 token and a payload, then truncate right after SCID,
    // removing the token-length varint byte.
    let full = make_raw_initial_header(8, 4, 0, 20);
    // Remove all bytes from the token-len position onward
    let token_varint_pos = 1 + 4 + 1 + 8 + 1 + 4; // first-byte + version + dcid_len + dcid + scid_len + scid
    let truncated = &full[..token_varint_pos];
    assert!(parse_quic_initial_header(truncated).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_inside_token() {
    // Token length varint says 10, but only 5 token bytes follow
    let mut full = make_raw_initial_header(8, 4, 10, 20);
    // The token bytes start after: 1 + 4 + 1 + 8 + 1 + 4 + 1 = 20 bytes
    // Truncate so only 5 of the 10 token bytes are present
    let token_start = 1 + 4 + 1 + 8 + 1 + 4 + 1;
    full.truncate(token_start + 5);
    assert!(parse_quic_initial_header(&full).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_at_payload_varint() {
    // Valid header through token, but payload-length varint is missing
    let full = make_raw_initial_header(8, 4, 0, 20);
    let payload_varint_pos = 1 + 4 + 1 + 8 + 1 + 4 + 1; // same as token_start for 0-token case
    let truncated = &full[..payload_varint_pos];
    assert!(parse_quic_initial_header(truncated).is_none());
}

#[test]
fn parse_quic_initial_header_truncated_inside_payload() {
    // payload_len says 50 but the buffer only has 10 payload bytes
    let mut full = make_raw_initial_header(8, 4, 0, 50);
    let payload_start = 1 + 4 + 1 + 8 + 1 + 4 + 1 + 1; // header bytes + 0-byte token + payload-varint(1 byte for value<64)
    full.truncate(payload_start + 10);
    assert!(parse_quic_initial_header(&full).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_dcid_len_21() {
    // QUIC_MAX_CID_LEN is 20; dcid_len=21 must be rejected
    let mut buf = vec![0xc3];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.push(21); // dcid_len > max
    buf.resize(QUIC_INITIAL_MIN_LEN, 0);
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_scid_len_21() {
    // scid_len=21 > QUIC_MAX_CID_LEN must be rejected
    let mut buf = vec![0xc3];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.push(8); // valid dcid_len
    buf.extend(std::iter::repeat_n(0xab, 8)); // 8-byte DCID
    buf.push(21); // scid_len > max
    buf.resize(QUIC_INITIAL_MIN_LEN, 0);
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_oversized_token_varint() {
    // Encode a token-length varint as an 8-byte varint with a huge value (>>2048),
    // so the subsequent buffer.get(offset..offset+token_len) check returns None.
    let mut buf = vec![0xc3];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.push(8); // dcid_len
    buf.extend(std::iter::repeat_n(0xab, 8));
    buf.push(4); // scid_len
    buf.extend(std::iter::repeat_n(0xcd, 4));
    // 8-byte varint with value u64::MAX >> 2 (maximum representable)
    buf.extend_from_slice(&encode_quic_varint(u64::MAX >> 2));
    buf.resize(QUIC_INITIAL_MIN_LEN, 0x00);
    // Parser must return None (usize overflow guard via try_into or slice OOB)
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_oversized_payload_varint() {
    // token_len=0, but payload_len is a huge varint
    let mut buf = vec![0xc3];
    buf.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    buf.push(8);
    buf.extend(std::iter::repeat_n(0xab, 8));
    buf.push(4);
    buf.extend(std::iter::repeat_n(0xcd, 4));
    buf.push(0x00); // token_len = 0 (1-byte varint)
    buf.extend_from_slice(&encode_quic_varint(u64::MAX >> 2));
    buf.resize(QUIC_INITIAL_MIN_LEN, 0x00);
    assert!(parse_quic_initial_header(&buf).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_garbage_all_zeros() {
    assert!(parse_quic_initial_header(&[0u8; 256]).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_garbage_all_ones() {
    assert!(parse_quic_initial_header(&[0xffu8; 512]).is_none());
}

#[test]
fn parse_quic_initial_header_rejects_random_garbage() {
    // A fixed "random" byte sequence unlikely to form a valid header
    let garbage: Vec<u8> = (0u8..=255).cycle().take(300).collect();
    // This must not panic; result may be Some or None
    let _ = parse_quic_initial_header(&garbage);
}

// ---- proptest: arbitrary byte vectors never cause a panic ----

use proptest::prelude::*;

proptest! {
    #[test]
    fn parse_quic_initial_header_never_panics(buf in proptest::collection::vec(any::<u8>(), 0..=2048)) {
        // The only contract tested here: the function must not panic.
        // It is allowed to return None for invalid inputs.
        let _ = parse_quic_initial_header(&buf);
    }
}
