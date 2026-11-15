use super::*;

#[test]
fn oversized_settings_stop_both_pmtu_observation_windows() {
    for ipv6 in [false, true] {
        // Control stream type, then SETTINGS length 65537 without payload or FIN.
        let server = Server::start_with_control(ipv6, b"h3", Some(&[0, 4, 0x80, 1, 0, 1]));
        let facts = run(&server, &server.config());
        server.finish();
        assert_eq!(facts.status, "INCONCLUSIVE", "{facts:?}");
        assert_eq!(facts.reason.as_deref(), Some("connection_closed"), "{facts:?}");
        assert!(facts.tls_validated && facts.fragmentation_prevented, "{facts:?}");
        assert!(!facts.observation_window_complete, "{facts:?}");
    }
}
