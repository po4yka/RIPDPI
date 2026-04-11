use super::*;

#[test]
fn build_fake_region_bytes_repeats_fake_stream_from_offset() {
    let fake = FakePacketPlan { bytes: b"ABCDEFG".to_vec(), fake_offset: 2, proto: ProtoInfo::default() };

    assert_eq!(build_fake_region_bytes(&fake, 0, 6), b"CDEFGC".to_vec());
    assert_eq!(build_fake_region_bytes(&fake, 3, 5), b"FGCDE".to_vec());
    assert_eq!(build_fake_region_bytes(&fake, 0, 0), Vec::<u8>::new());
}

#[test]
fn build_fake_region_bytes_falls_back_to_zeroes_when_fake_stream_is_empty() {
    let fake = FakePacketPlan { bytes: b"ABC".to_vec(), fake_offset: 3, proto: ProtoInfo::default() };

    assert_eq!(build_fake_region_bytes(&fake, 7, 4), vec![0, 0, 0, 0]);
}

#[test]
fn normalize_fake_tls_size_all_branches() {
    // negative -> saturating_sub
    assert_eq!(normalize_fake_tls_size(-5, 100), 95);
    assert_eq!(normalize_fake_tls_size(-200, 100), 0);
    // zero -> input_len
    assert_eq!(normalize_fake_tls_size(0, 100), 100);
    // positive -> explicit target size
    assert_eq!(normalize_fake_tls_size(200, 100), 200);
    // positive in range -> value
    assert_eq!(normalize_fake_tls_size(50, 100), 50);
}

#[test]
fn build_fake_packet_applies_rndsni_and_dupsid_in_order() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_mod = FM_ORIG | FM_RAND | FM_RNDSNI | FM_DUPSID;
    let payload = DEFAULT_FAKE_TLS;
    let expected_sid = payload[44..44 + payload[43] as usize].to_vec();
    let original_host = ripdpi_packets::parse_tls(payload).expect("original tls host").to_vec();

    let fake = build_fake_packet(&group, payload, 19).expect("fake packet");
    let fake_host = ripdpi_packets::parse_tls(&fake.bytes).expect("fake tls host").to_vec();

    assert_ne!(fake_host, original_host);
    assert_eq!(&fake.bytes[44..44 + fake.bytes[43] as usize], expected_sid.as_slice());
}

#[test]
fn build_fake_packet_applies_fake_tls_size_to_default_and_orig_bases() {
    let mut default_group = DesyncGroup::new(0);
    default_group.actions.fake_mod = FM_RNDSNI;
    default_group.actions.fake_tls_size = (DEFAULT_FAKE_TLS.len() + 12) as i32;

    let default_fake = build_fake_packet(&default_group, DEFAULT_FAKE_TLS, 3).expect("default fake tls");
    assert_eq!(default_fake.bytes.len(), DEFAULT_FAKE_TLS.len() + 12);

    let mut orig_group = DesyncGroup::new(0);
    orig_group.actions.fake_mod = FM_ORIG | FM_RNDSNI;
    orig_group.actions.fake_tls_size = (DEFAULT_FAKE_TLS.len() + 8) as i32;

    let orig_fake = build_fake_packet(&orig_group, DEFAULT_FAKE_TLS, 5).expect("orig fake tls");
    assert_eq!(orig_fake.bytes.len(), DEFAULT_FAKE_TLS.len() + 8);
}

#[test]
fn build_fake_packet_ignores_tls_mods_for_http_payloads() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_mod = FM_RAND | FM_RNDSNI | FM_DUPSID | FM_PADENCAP;

    let fake = build_fake_packet(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 11).expect("http fake");

    assert_eq!(fake.bytes, DEFAULT_FAKE_HTTP);
    assert_eq!(fake.proto.kind, IS_HTTP);
}

#[test]
fn build_fake_packet_padencap_keeps_valid_tls() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_mod = FM_PADENCAP;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("padencap fake");

    assert!(ripdpi_packets::parse_tls(&fake.bytes).is_some());
    assert_eq!(fake.bytes.len(), DEFAULT_FAKE_TLS.len());
    assert!(fake.fake_offset <= fake.bytes.len());
}

#[test]
fn build_hostfake_bytes_preserves_length_and_template_suffix() {
    let fake = build_hostfake_bytes(b"video.example.com", Some("googlevideo.com"), 17);

    assert_eq!(fake.len(), b"video.example.com".len());
    assert!(fake.iter().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'-')));
    assert!(std::str::from_utf8(&fake).unwrap().ends_with("video.com"));
}

#[test]
fn build_fake_packet_uses_selected_http_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.http_fake_profile = HttpFakeProfile::CloudflareGet;

    let fake = build_fake_packet(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 7).expect("http fake");
    let parsed = parse_http(&fake.bytes).expect("parse fake http");

    assert_eq!(parsed.host, b"www.cloudflare.com");
}

#[test]
fn build_fake_packet_uses_selected_tls_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.tls_fake_profile = TlsFakeProfile::GoogleChrome;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("tls fake");
    let parsed = parse_tls(&fake.bytes).expect("parse fake tls");

    assert_eq!(parsed, b"www.google.com");
}

#[test]
fn build_fake_packet_can_clone_captured_client_hello() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_tls_source = ripdpi_config::FakePacketSource::CapturedClientHello;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("tls fake");

    assert_eq!(fake.bytes, DEFAULT_FAKE_TLS);
}

#[test]
fn build_fake_packet_avoids_517_byte_tls_size_when_padding_can_be_tuned() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_tls_size = 517;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("tls fake");

    assert_ne!(fake.bytes.len(), 517);
    assert!(ripdpi_packets::is_tls_client_hello(&fake.bytes));
}

#[test]
fn build_secondary_fake_packet_uses_secondary_tls_profile_without_raw_fake_override() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_data = Some(b"not-used".to_vec());
    group.actions.fake_tls_secondary_profile = Some(TlsFakeProfile::GoogleChrome);

    let fake = build_secondary_fake_packet(&group, DEFAULT_FAKE_TLS, 13)
        .expect("secondary fake")
        .expect("secondary fake should exist");
    let parsed = parse_tls(&fake.bytes).expect("parse fake tls");

    assert_eq!(parsed, b"www.google.com");
}

#[test]
fn build_seqovl_fake_prefix_profile_reuses_fake_packet_builder() {
    let mut group = DesyncGroup::new(0);
    group.actions.tls_fake_profile = TlsFakeProfile::GoogleChrome;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 11).expect("fake packet");
    let prefix = build_seqovl_fake_prefix(&group, DEFAULT_FAKE_TLS, 11, 6, ripdpi_config::SeqOverlapFakeMode::Profile)
        .expect("seqovl prefix");

    assert_eq!(prefix, build_fake_region_bytes(&fake, 0, 6));
}

#[test]
fn build_seqovl_fake_prefix_rand_uses_seeded_random_bytes() {
    let group = DesyncGroup::new(0);
    let prefix = build_seqovl_fake_prefix(&group, DEFAULT_FAKE_TLS, 23, 8, ripdpi_config::SeqOverlapFakeMode::Rand)
        .expect("seqovl rand prefix");
    let mut rng = OracleRng::seeded(23);
    let expected = (0..8).map(|_| rng.next_u8()).collect::<Vec<_>>();

    assert_eq!(prefix, expected);
}
