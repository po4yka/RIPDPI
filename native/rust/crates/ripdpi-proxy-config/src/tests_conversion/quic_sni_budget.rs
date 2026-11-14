use super::*;

#[test]
fn ui_payload_accepts_bounded_quic_sni_split_counts() {
    for counts in [vec![0], vec![64], vec![32, 32]] {
        let mut ui = minimal_ui();
        ui.chains.udp_steps = counts.into_iter().map(|count| udp_step("quic_sni_split", count)).collect();
        runtime_config_from_payload(ui_payload(ui)).expect("bounded QUIC SNI split count");
    }
}

#[test]
fn ui_payload_rejects_excessive_quic_sni_split_counts() {
    for counts in [vec![65], vec![32, 33], vec![i32::MAX, i32::MAX]] {
        let mut ui = minimal_ui();
        ui.chains.udp_steps = counts.into_iter().map(|count| udp_step("quic_sni_split", count)).collect();
        let error = runtime_config_from_payload(ui_payload(ui)).expect_err("excessive QUIC SNI split count");
        assert!(error.to_string().contains("quic_sni_split"));
    }
}
