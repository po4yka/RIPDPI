use ripdpi_anytls::padding::{PaddingAction, PaddingPlan, PaddingScheme, PaddingStep, DEFAULT_PADDING_SCHEME};

const DEFAULT_PADDING_MD5: &str = "75cff2ad89aadf5e257059ee571ebe11";

#[test]
fn default_padding_scheme_raw_bytes_and_md5_match_protocol() {
    let scheme = PaddingScheme::parse(DEFAULT_PADDING_SCHEME.as_bytes()).expect("default padding scheme parses");

    assert_eq!(scheme.stop(), 8);
    assert_eq!(scheme.raw_scheme(), DEFAULT_PADDING_SCHEME.as_bytes());
    assert_eq!(scheme.padding_md5(), DEFAULT_PADDING_MD5);
}

#[test]
fn stop_threshold_pads_only_packet_indexes_before_stop() {
    let scheme = PaddingScheme::parse(b"stop=2\n0=30-30\n1=40-40\n2=50-50").expect("scheme parses");

    assert_eq!(scheme.plan_for_packet(0, 0).expect("packet 0 plan").steps(), &[PaddingStep::PayloadSize(30)]);
    assert_eq!(scheme.plan_for_packet(1, 0).expect("packet 1 plan").steps(), &[PaddingStep::PayloadSize(40)]);
    assert_eq!(scheme.plan_for_packet(2, 0).expect("packet 2 direct"), PaddingPlan::direct());
}

#[test]
fn missing_rule_before_stop_sends_packet_directly() {
    let scheme = PaddingScheme::parse(b"stop=8\n0=30-30\n2=50-50").expect("scheme parses");

    assert_eq!(scheme.plan_for_packet(1, 0).expect("packet 1 direct"), PaddingPlan::direct());
}

#[test]
fn parser_preserves_check_mark_and_fixed_ranges_in_order() {
    let scheme = PaddingScheme::parse(b"stop=4\n2=400-400,c,500-500,c,600-600").expect("scheme parses");

    assert_eq!(
        scheme.plan_for_packet(2, 0).expect("packet 2 plan").steps(),
        &[
            PaddingStep::PayloadSize(400),
            PaddingStep::CheckIfNoMoreData,
            PaddingStep::PayloadSize(500),
            PaddingStep::CheckIfNoMoreData,
            PaddingStep::PayloadSize(600),
        ],
    );
}

#[test]
fn parser_rejects_invalid_or_missing_stop() {
    assert!(PaddingScheme::parse(b"0=30-30").is_err());
    assert!(PaddingScheme::parse(b"stop=not-a-number\n0=30-30").is_err());
    assert!(PaddingScheme::parse(b"stop=0\n0=30-30").is_err());
}

#[test]
fn range_bounds_are_normalized_and_deterministic_for_tests() {
    let scheme = PaddingScheme::parse(b"stop=2\n0=30-10\n1=100-101").expect("scheme parses");

    assert_eq!(scheme.plan_for_packet(0, 20).expect("packet 0 plan").steps(), &[PaddingStep::PayloadSize(30)]);
    assert_eq!(scheme.plan_for_packet(1, 0).expect("packet 1 lower").steps(), &[PaddingStep::PayloadSize(100)]);
    assert_eq!(scheme.plan_for_packet(1, 1).expect("packet 1 upper").steps(), &[PaddingStep::PayloadSize(101)]);
}

#[test]
fn padding0_auth_plan_never_splits_and_reports_padding_length() {
    let scheme = PaddingScheme::parse(b"stop=2\n0=30-30,40-40\n1=20-20").expect("scheme parses");

    let auth = scheme.auth_padding0_len(0).expect("auth padding");

    assert_eq!(auth, 30);
}

#[test]
fn write_plan_splits_payload_and_appends_waste_padding() {
    let scheme = PaddingScheme::parse(b"stop=3\n1=20-20").expect("scheme parses");

    let actions = scheme.write_plan_for_packet(1, b"abcdef", 0).expect("write plan");

    assert_eq!(
        actions,
        vec![PaddingAction::Payload(b"abcdef".to_vec()), PaddingAction::Waste { len: 7 }],
        "20 byte TLS plaintext target minus 6 payload bytes minus 7 byte cmdWaste header leaves 7 bytes of waste",
    );
}

#[test]
fn check_mark_stops_later_padding_when_payload_is_exhausted() {
    let scheme = PaddingScheme::parse(b"stop=3\n1=6-6,c,20-20").expect("scheme parses");

    let actions = scheme.write_plan_for_packet(1, b"abcdef", 0).expect("write plan");

    assert_eq!(actions, vec![PaddingAction::Payload(b"abcdef".to_vec())]);
}
