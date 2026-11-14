use super::*;

use ripdpi_config::FM_RNDSNI;

fn seqovl_ui(fake_mode: &str) -> ProxyUiConfig {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![seqovl_step("host+1", 12, fake_mode)];
    ui.fake_packets.fake_sni = "decoy.example.net".to_string();
    ui
}

#[test]
fn seqovl_profile_fake_mode_fills_fixed_fake_sni() {
    let config = runtime_config_from_payload(ui_payload(seqovl_ui("profile"))).expect("runtime config");

    assert_eq!(config.groups[0].actions.fake_sni_list, vec!["decoy.example.net".to_string()]);
}

#[test]
fn seqovl_profile_fake_mode_applies_randomized_sni() {
    let mut ui = seqovl_ui("profile");
    ui.fake_packets.fake_tls_sni_mode = "randomized".to_string();

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let actions = &config.groups[0].actions;

    assert_ne!(actions.fake_mod & FM_RNDSNI, 0);
    assert!(actions.fake_sni_list.is_empty());
}

#[test]
fn seqovl_rand_fake_mode_without_fake_steps_leaves_fake_sni_unset() {
    let config = runtime_config_from_payload(ui_payload(seqovl_ui("rand"))).expect("runtime config");

    assert!(config.groups[0].actions.fake_sni_list.is_empty());
}

#[test]
fn rotation_candidate_fake_step_fills_fake_sni_and_offset() {
    let mut ui = minimal_ui();
    ui.fake_packets.fake_sni = "decoy.example.net".to_string();
    ui.fake_packets.fake_offset_marker = "host+1".to_string();
    ui.chains.tcp_rotation = Some(ProxyUiTcpRotationConfig {
        candidates: vec![ProxyUiTcpRotationCandidate { tcp_steps: vec![tcp_step("fake", "host+1")] }],
        ..Default::default()
    });

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let actions = &config.groups[0].actions;

    assert_eq!(actions.fake_sni_list, vec!["decoy.example.net".to_string()]);
    assert!(actions.fake_offset.is_some());
}

fn fake_sni_ui(value: &str) -> ProxyUiConfig {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
    ui.fake_packets.fake_sni = value.to_string();
    ui
}

#[test]
fn fixed_fake_sni_is_normalized() {
    for (input, expected) in [("WWW.Example.org.", "www.example.org"), ("www.iana.org", "www.iana.org")] {
        let config = runtime_config_from_payload(ui_payload(fake_sni_ui(input))).expect("runtime config");

        assert_eq!(config.groups[0].actions.fake_sni_list, vec![expected.to_string()]);
    }
}

#[test]
fn fixed_fake_sni_rejects_non_hostnames() {
    let too_long = format!("{}.com", "a".repeat(300));
    for value in ["bad host/\u{fc}", "1.2.3.4", "::1", "host:443", too_long.as_str()] {
        let err = runtime_config_from_payload(ui_payload(fake_sni_ui(value))).expect_err(value);

        assert!(err.to_string().contains("fakePackets.fakeSni"), "{value}: {err}");
    }
}
