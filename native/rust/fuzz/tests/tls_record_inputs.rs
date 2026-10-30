#[path = "../fuzz_targets/common.rs"]
mod common;

const RECORD: &[u8] = &[0x16, 3, 3, 0, 4, 1, 2, 3, 4];

#[test]
fn split_preserves_payload_and_writes_both_record_headers() {
    assert_eq!(common::split_tls_record(RECORD, 2), [0x16, 3, 3, 0, 2, 1, 2, 0x16, 3, 3, 0, 2, 3, 4]);
}

#[test]
fn split_at_either_boundary_keeps_an_empty_record() {
    assert_eq!(common::split_tls_record(RECORD, 0), [0x16, 3, 3, 0, 0, 0x16, 3, 3, 0, 4, 1, 2, 3, 4]);
    assert_eq!(common::split_tls_record(RECORD, 4), [0x16, 3, 3, 0, 4, 1, 2, 3, 4, 0x16, 3, 3, 0, 0]);
}

#[test]
fn split_preserves_declared_length_for_truncated_record() {
    let truncated_record = [0x16, 3, 3, 0, 6, 1, 2, 3, 4];
    assert_eq!(common::split_tls_record(&truncated_record, 2), [0x16, 3, 3, 0, 2, 1, 2, 0x16, 3, 3, 0, 4, 3, 4]);
}

#[test]
fn invalid_header_and_out_of_bounds_offsets_leave_input_unchanged() {
    for len in 0..5 {
        assert_eq!(common::split_tls_record(&RECORD[..len], 0), &RECORD[..len]);
    }
    assert_eq!(common::split_tls_record(RECORD, 5), RECORD);
    assert_eq!(common::split_tls_record(RECORD, usize::MAX), RECORD);
    let short_declared_record = [0x16, 3, 3, 0, 1, 1, 2, 3, 4];
    assert_eq!(common::split_tls_record(&short_declared_record, 2), short_declared_record);
    let truncated_record = [0x16, 3, 3, 0, 4, 1];
    assert_eq!(common::split_tls_record(&truncated_record, 2), truncated_record);
}

#[test]
fn structured_split_reaches_the_real_client_hello_parser() {
    let packet = ripdpi_packets::tls_fake_profile_bytes(ripdpi_packets::TlsFakeProfile::GoogleChrome);
    let split = common::split_tls_record(packet, 112);
    assert_eq!(split.len(), packet.len() + 5);
    assert!(ripdpi_desync::parse_client_hello_offsets(&split).is_ok());
}
