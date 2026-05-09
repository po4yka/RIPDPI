use super::*;

#[test]
fn strategy_fallback_maps_all_families() {
    assert_eq!(strategy_fallback_family("seg_mid_sni"), Some("seg_pre_sni"));
    assert_eq!(strategy_fallback_family("rec_mid_sni"), Some("rec_pre_sni"));
    assert_eq!(strategy_fallback_family("disorder"), Some("split"));
    assert_eq!(strategy_fallback_family("seqovl"), Some("split"));
    assert_eq!(strategy_fallback_family("tlsrec_seqovl"), Some("tlsrec_split"));
    assert_eq!(strategy_fallback_family("disoob"), Some("oob"));
    assert_eq!(strategy_fallback_family("fakeddisorder"), Some("fakedsplit"));
    assert_eq!(strategy_fallback_family("split"), None);
    assert_eq!(strategy_fallback_family("oob"), None);
    assert_eq!(strategy_fallback_family("fake"), None);
    assert_eq!(strategy_fallback_family("multidisorder"), None);
    assert_eq!(strategy_fallback_family("unknown"), None);
}

#[test]
fn write_action_name_maps_all_families() {
    assert_eq!(write_action_name("split"), "write_split");
    assert_eq!(write_action_name("seg_pre_sni"), "write_split");
    assert_eq!(write_action_name("seg_mid_sni"), "write_split");
    assert_eq!(write_action_name("seg_post_sni"), "write_split");
    assert_eq!(write_action_name("rec_pre_sni"), "write_tlsrec");
    assert_eq!(write_action_name("rec_mid_sni"), "write_tlsrec");
    assert_eq!(write_action_name("two_phase_send"), "write_split");
    assert_eq!(write_action_name("seqovl"), "write_seqovl");
    assert_eq!(write_action_name("tlsrec_seqovl"), "write_seqovl");
    assert_eq!(write_action_name("disorder"), "write_disorder");
    assert_eq!(write_action_name("oob"), "write_oob");
    assert_eq!(write_action_name("disoob"), "write_disoob");
    assert_eq!(write_action_name("fake"), "write_fake");
    assert_eq!(write_action_name("fakedsplit"), "write_fakesplit");
    assert_eq!(write_action_name("fakeddisorder"), "write_fakeddisorder");
    assert_eq!(write_action_name("hostfake"), "write_hostfake");
    assert_eq!(write_action_name("unknown"), "write");
}

#[test]
fn set_ttl_action_name_maps_variants() {
    assert_eq!(set_ttl_action_name("disorder"), "set_ttl_disorder");
    assert_eq!(set_ttl_action_name("disoob"), "set_ttl_disoob");
    assert_eq!(set_ttl_action_name("fakeddisorder"), "set_ttl_fakeddisorder");
    assert_eq!(set_ttl_action_name("split"), "set_ttl");
    assert_eq!(set_ttl_action_name("oob"), "set_ttl");
}

#[test]
fn restore_ttl_action_name_maps_variants() {
    assert_eq!(restore_ttl_action_name("disorder"), "restore_default_ttl_disorder");
    assert_eq!(restore_ttl_action_name("disoob"), "restore_default_ttl_disoob");
    assert_eq!(restore_ttl_action_name("fakeddisorder"), "restore_default_ttl_fakeddisorder");
    assert_eq!(restore_ttl_action_name("split"), "restore_default_ttl");
}

#[test]
fn await_writable_action_name_maps_all() {
    assert_eq!(await_writable_action_name("split"), "await_writable_split");
    assert_eq!(await_writable_action_name("seg_pre_sni"), "await_writable_split");
    assert_eq!(await_writable_action_name("seg_mid_sni"), "await_writable_split");
    assert_eq!(await_writable_action_name("seg_post_sni"), "await_writable_split");
    assert_eq!(await_writable_action_name("rec_pre_sni"), "await_writable_tlsrec");
    assert_eq!(await_writable_action_name("rec_mid_sni"), "await_writable_tlsrec");
    assert_eq!(await_writable_action_name("two_phase_send"), "await_writable_split");
    assert_eq!(await_writable_action_name("seqovl"), "await_writable_seqovl");
    assert_eq!(await_writable_action_name("tlsrec_seqovl"), "await_writable_seqovl");
    assert_eq!(await_writable_action_name("disorder"), "await_writable_disorder");
    assert_eq!(await_writable_action_name("oob"), "await_writable_oob");
    assert_eq!(await_writable_action_name("disoob"), "await_writable_disoob");
    assert_eq!(await_writable_action_name("fakedsplit"), "await_writable_fakesplit");
    assert_eq!(await_writable_action_name("fakeddisorder"), "await_writable_fakeddisorder");
    assert_eq!(await_writable_action_name("hostfake"), "await_writable_hostfake");
    assert_eq!(await_writable_action_name("unknown"), "await_writable");
}

#[test]
fn ipfrag2_fallback_matches_expected_kinds() {
    assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::InvalidInput));
    assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::WouldBlock));
    assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::Unsupported));
    assert!(!should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::ConnectionReset));
    assert!(!should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::BrokenPipe));
}

#[test]
fn seqovl_fallback_matches_expected_kinds() {
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::InvalidInput));
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::WouldBlock));
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::Unsupported));
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::PermissionDenied));
    assert!(!should_fallback_seqovl_error_kind(io::ErrorKind::ConnectionReset));
}
