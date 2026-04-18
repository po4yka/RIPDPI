use super::*;

fn minimal_ui_config() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![tcp_step("disorder", "host+1")];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config
}

#[test]
fn build_strategy_probe_suite_unknown_id_returns_error() {
    let base = minimal_ui_config();
    let result = build_strategy_probe_suite("nonexistent_v99", &base);
    assert!(result.is_err());
    assert!(result.unwrap_err().contains("Unsupported"));
}

#[test]
fn build_strategy_probe_suite_quick_v1_returns_candidates() {
    let base = minimal_ui_config();
    let suite = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1 suite");
    assert!(!suite.tcp_candidates.is_empty());
    assert!(!suite.quic_candidates.is_empty());
    assert!(suite.short_circuit_hostfake);
    assert!(suite.short_circuit_quic_burst);
}

#[test]
fn build_strategy_probe_suite_full_matrix_has_extra_candidates() {
    let base = minimal_ui_config();
    let quick = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1");
    let full = build_strategy_probe_suite("full_matrix_v1", &base).expect("full_matrix_v1");
    assert!(full.tcp_candidates.len() > quick.tcp_candidates.len());
    assert!(!full.short_circuit_hostfake);
    assert!(!full.short_circuit_quic_burst);
    assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "circular_tlsrec_split"));
    assert!(!quick.tcp_candidates.iter().any(|candidate| candidate.id == "circular_tlsrec_split"));
    assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_fakedsplit_altorder1"));
    assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_fakedsplit_altorder2"));
    assert!(!quick.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_fakedsplit_altorder1"));
}

#[test]
fn circular_tlsrec_split_candidate_uses_expected_rotation_defaults() {
    let candidate = build_circular_tlsrec_split_candidate(&minimal_ui_config());
    let rotation = candidate.chains.tcp_rotation.as_ref().expect("tcp rotation");

    assert_eq!(candidate.chains.tcp_steps, build_tlsrec_split_host_candidate(&minimal_ui_config()).chains.tcp_steps);
    assert_eq!(rotation.fails, 3);
    assert_eq!(rotation.retrans, 3);
    assert_eq!(rotation.seq, 65_536);
    assert_eq!(rotation.rst, 1);
    assert_eq!(rotation.time_secs, 60);
    assert_eq!(rotation.candidates.len(), 3);
    assert_eq!(
        rotation.candidates[0].tcp_steps,
        build_tlsrec_hostfake_candidate(&minimal_ui_config(), true).chains.tcp_steps
    );
    assert_eq!(
        rotation.candidates[1].tcp_steps,
        build_tlsrec_fake_rich_candidate(&minimal_ui_config()).chains.tcp_steps
    );
    assert_eq!(rotation.candidates[2].tcp_steps, build_split_host_candidate(&minimal_ui_config()).chains.tcp_steps);
}

#[test]
fn tlsrec_fake_seqgroup_candidate_sets_seqgroup_ip_id_mode() {
    let candidate = build_tlsrec_fake_seqgroup_candidate(&minimal_ui_config());

    assert_eq!(candidate.fake_packets.ip_id_mode, "seqgroup");
    assert_eq!(candidate.chains.tcp_steps, build_tlsrec_fake_rich_candidate(&minimal_ui_config()).chains.tcp_steps);
}

#[test]
fn production_fake_candidates_use_coherent_chrome_profile_bytes() {
    let candidate = build_tlsrec_fake_rich_candidate(&minimal_ui_config());

    assert_eq!(candidate.fake_packets.tls_fake_profile, TLS_FAKE_PROFILE_GOOGLE_CHROME);
    assert_eq!(candidate.fake_packets.fake_sni, "www.google.com");
    assert!(!candidate.fake_packets.fake_tls_use_original);
    assert!(!candidate.fake_packets.fake_tls_randomize);
    assert!(!candidate.fake_packets.fake_tls_dup_session_id);
    assert_eq!(candidate.fake_packets.fake_tls_sni_mode, "fixed");
}

#[test]
fn adaptive_fake_ttl_keeps_random_fake_behavior_lab_only() {
    let spec = build_adaptive_fake_ttl_spec(&minimal_ui_config());

    assert_eq!(spec.emitter_tier, StrategyEmitterTier::LabDiagnosticsOnly);
    assert!(spec.config.fake_packets.fake_tls_use_original);
    assert!(spec.config.fake_packets.fake_tls_randomize);
    assert!(spec.config.fake_packets.fake_tls_dup_session_id);
    assert_eq!(spec.config.fake_packets.fake_tls_sni_mode, "randomized");
}

#[test]
fn seqovl_candidates_always_included() {
    let base = minimal_ui_config();
    let quick = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1");
    let full = build_strategy_probe_suite("full_matrix_v1", &base).expect("full_matrix_v1");

    // SeqOverlap candidates are now unconditionally included; the runtime
    // falls back to split when TCP_REPAIR/CAP_NET_ADMIN is unavailable.
    assert!(quick.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_seqovl_midsld"));
    assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_seqovl_midsld"));
    assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_seqovl_sniext"));
}

#[test]
fn build_tlsrec_seqovl_candidate_sets_hard_gate_and_fields() {
    let config = build_tlsrec_seqovl_candidate(&minimal_ui_config(), "midsld");
    let steps = &config.chains.tcp_steps;

    assert_eq!(steps.len(), 2);
    assert_eq!(steps[0].kind, "tlsrec");
    assert_eq!(steps[0].marker, "extlen");
    assert_eq!(steps[1].kind, "seqovl");
    assert_eq!(steps[1].marker, "midsld");
    assert_eq!(steps[1].overlap_size, 12);
    assert_eq!(steps[1].fake_mode, "profile");
    let filter = steps[1].activation_filter.as_ref().expect("seqovl activation filter");
    assert_eq!(filter.round.as_ref().and_then(|value| value.start), Some(1));
    assert_eq!(filter.round.as_ref().and_then(|value| value.end), Some(1));
    assert_eq!(filter.stream_bytes.as_ref().and_then(|value| value.start), Some(0));
    assert_eq!(filter.stream_bytes.as_ref().and_then(|value| value.end), Some(1500));
}

#[test]
fn build_tcp_candidates_marks_ech_candidates_as_ech_only_and_targets_echext() {
    let candidates = build_tcp_candidates(&minimal_ui_config());
    let ech_split = candidates.iter().find(|candidate| candidate.id == "ech_split").expect("ech_split candidate");
    let ech_tlsrec = candidates.iter().find(|candidate| candidate.id == "ech_tlsrec").expect("ech_tlsrec candidate");

    assert_eq!(ech_split.eligibility, CandidateEligibility::RequiresEchCapability);
    assert_eq!(ech_split.config.chains.tcp_steps.len(), 1);
    assert_eq!(ech_split.config.chains.tcp_steps[0].kind, "split");
    assert_eq!(ech_split.config.chains.tcp_steps[0].marker, "echext");
    assert!(ech_split.notes.iter().any(|note| note.contains("ECH-capable HTTPS path")));

    assert_eq!(ech_tlsrec.eligibility, CandidateEligibility::RequiresEchCapability);
    assert_eq!(ech_tlsrec.config.chains.tcp_steps.len(), 1);
    assert_eq!(ech_tlsrec.config.chains.tcp_steps[0].kind, "tlsrec");
    assert_eq!(ech_tlsrec.config.chains.tcp_steps[0].marker, "echext");
    assert!(ech_tlsrec.notes.iter().any(|note| note.contains("ECH-capable HTTPS path")));
}

#[test]
fn build_full_matrix_tcp_candidates_keeps_fake_flag_variants_lab_only() {
    let quick_candidates = build_tcp_candidates(&minimal_ui_config());
    let full_candidates = build_full_matrix_tcp_candidates(&minimal_ui_config());
    let fake_synfin = full_candidates.iter().find(|candidate| candidate.id == "fake_synfin").expect("fake_synfin");
    let fake_pshurg = full_candidates.iter().find(|candidate| candidate.id == "fake_pshurg").expect("fake_pshurg");

    assert!(!quick_candidates.iter().any(|candidate| candidate.id == "fake_synfin"));
    assert!(!quick_candidates.iter().any(|candidate| candidate.id == "fake_pshurg"));
    assert_eq!(fake_synfin.family, "fake_flags");
    assert_eq!(fake_synfin.emitter_tier, StrategyEmitterTier::LabDiagnosticsOnly);
    assert_eq!(fake_synfin.config.chains.tcp_steps[1].kind, "fake");
    assert_eq!(fake_synfin.config.chains.tcp_steps[1].tcp_flags_set, "syn|fin");
    assert_eq!(fake_pshurg.family, "fake_flags");
    assert_eq!(fake_pshurg.emitter_tier, StrategyEmitterTier::LabDiagnosticsOnly);
    assert_eq!(fake_pshurg.config.chains.tcp_steps[1].tcp_flags_set, "psh|urg");
}

#[test]
fn fakedsplit_altorder_candidates_set_expected_order() {
    let alt1 = build_tlsrec_fakedsplit_altorder_candidate(&minimal_ui_config(), "1");
    let alt2 = build_tlsrec_fakedsplit_altorder_candidate(&minimal_ui_config(), "2");

    assert_eq!(alt1.chains.tcp_steps[1].kind, "fakedsplit");
    assert_eq!(alt1.chains.tcp_steps[1].fake_order, "1");
    assert_eq!(alt2.chains.tcp_steps[1].kind, "fakedsplit");
    assert_eq!(alt2.chains.tcp_steps[1].fake_order, "2");
}

#[test]
fn ipfrag_candidates_follow_platform_capability_probe() {
    let base = minimal_ui_config();
    let tcp_candidates = build_tcp_candidates(&base);
    let full_tcp_candidates = build_full_matrix_tcp_candidates(&base);
    let quic_candidates = build_quic_candidates(&base);
    let full_quic_candidates = build_full_matrix_quic_candidates(&base);
    let tcp_ipfrag_capable = supports_tcp_ip_fragmentation();
    let udp_ipfrag_capable = supports_udp_ip_fragmentation();

    assert_eq!(tcp_candidates.iter().any(|candidate| candidate.id == "ipfrag2"), tcp_ipfrag_capable);
    assert_eq!(full_tcp_candidates.iter().any(|candidate| candidate.id == "ipfrag2_hopbyhop"), tcp_ipfrag_capable);
    assert!(!tcp_candidates.iter().any(|candidate| candidate.id == "ipfrag2_hopbyhop"));
    assert_eq!(quic_candidates.iter().any(|candidate| candidate.id == "quic_ipfrag2"), udp_ipfrag_capable);
    assert_eq!(
        full_quic_candidates.iter().any(|candidate| candidate.id == "quic_ipfrag2_hopbyhop"),
        udp_ipfrag_capable
    );
    assert!(!quic_candidates.iter().any(|candidate| candidate.id == "quic_ipfrag2_hopbyhop"));
}

#[test]
fn rooted_and_lab_candidates_are_partitioned_between_quick_and_full_matrix() {
    let base = minimal_ui_config();
    let quick_candidates = build_tcp_candidates(&base);
    let full_candidates = build_full_matrix_tcp_candidates(&base);

    assert!(quick_candidates.iter().any(|candidate| candidate.id == "multi_disorder"));
    assert_eq!(
        quick_candidates
            .iter()
            .find(|candidate| candidate.id == "multi_disorder")
            .expect("multi_disorder")
            .emitter_tier,
        StrategyEmitterTier::RootedProduction
    );
    assert!(!quick_candidates.iter().any(|candidate| candidate.id == "fake_rst"));
    assert!(full_candidates.iter().any(|candidate| candidate.id == "fake_rst"));
    assert_eq!(
        full_candidates.iter().find(|candidate| candidate.id == "fake_rst").expect("fake_rst").emitter_tier,
        StrategyEmitterTier::LabDiagnosticsOnly
    );
}

#[test]
fn seqovl_candidates_keep_rooted_tier_and_fallback_metadata() {
    let candidates = build_tcp_candidates(&minimal_ui_config());
    let seqovl = candidates.iter().find(|candidate| candidate.id == "tlsrec_seqovl_midsld").expect("seqovl");

    assert_eq!(seqovl.emitter_tier, StrategyEmitterTier::RootedProduction);
    assert!(seqovl.exact_emitter_requires_root);
    assert_eq!(seqovl.approximate_fallback_family, Some("tlsrec_split"));
}

#[test]
fn tfo_candidates_are_suppressed_for_upstream_relay_routes() {
    let mut base = minimal_ui_config();
    base.upstream_relay.enabled = true;
    base.upstream_relay.kind = "vless_reality".to_string();

    let tcp_candidates = build_tcp_candidates(&base);

    assert!(!tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_split_host_tfo"));
    assert!(!tcp_candidates.iter().any(|candidate| candidate.id == "split_host_tfo"));
}

#[test]
fn ipfrag_capability_helpers_split_tcp_and_udp_requirements() {
    let udp_only =
        ripdpi_runtime::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: false };
    assert!(!supports_tcp_ip_fragmentation_for(udp_only));
    assert!(supports_udp_ip_fragmentation_for(udp_only));

    let tcp_and_udp =
        ripdpi_runtime::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true };
    assert!(supports_tcp_ip_fragmentation_for(tcp_and_udp));
    assert!(supports_udp_ip_fragmentation_for(tcp_and_udp));
}

#[test]
fn default_runtime_encrypted_dns_context_returns_adguard_doh() {
    let ctx = default_runtime_encrypted_dns_context();
    assert_eq!(ctx.protocol, "doh");
    assert_eq!(ctx.host, "dns.adguard-dns.com");
    assert!(ctx.doh_url.as_deref().unwrap_or("").contains("dns.adguard-dns.com"));
    assert!(!ctx.bootstrap_ips.is_empty());
    assert!(ctx.bootstrap_ips.iter().any(|ip| ip == "94.140.14.14"));
}

#[test]
fn strategy_probe_encrypted_dns_label_uses_doh_url_when_present() {
    let ctx = default_runtime_encrypted_dns_context();
    let label = strategy_probe_encrypted_dns_label(&ctx);
    assert!(label.contains("dns.adguard-dns.com"));
}

#[test]
fn strategy_probe_encrypted_dns_label_falls_back_to_host_port() {
    let ctx = ProxyEncryptedDnsContext {
        resolver_id: None,
        protocol: "dot".to_string(),
        host: "example.com".to_string(),
        port: 853,
        tls_server_name: None,
        bootstrap_ips: Vec::new(),
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    };
    let label = strategy_probe_encrypted_dns_label(&ctx);
    assert_eq!(label, "example.com:853");
}

#[test]
fn candidate_pause_ms_failed_is_larger() {
    let spec = candidate_spec("test", "Test", "test", minimal_ui_config());
    let ok_pause = candidate_pause_ms(42, &spec, false);
    let fail_pause = candidate_pause_ms(42, &spec, true);
    assert!(fail_pause > ok_pause, "failed pause {fail_pause} should exceed ok pause {ok_pause}");
}

#[test]
fn sanitize_current_probe_config_disables_autolearn() {
    let mut base = minimal_ui_config();
    base.host_autolearn.enabled = true;
    base.host_autolearn.store_path = Some("/tmp/test".to_string());
    let sanitized = sanitize_current_probe_config(&base);
    assert!(!sanitized.host_autolearn.enabled);
    assert!(sanitized.host_autolearn.store_path.is_none());
}

#[test]
fn strategy_probe_base_resets_desync_fields() {
    let mut base = minimal_ui_config();
    base.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
    let probe = strategy_probe_base(&base);
    assert!(probe.chains.tcp_steps.is_empty());
    assert!(probe.protocols.desync_http);
    assert!(probe.protocols.desync_https);
    assert!(!probe.protocols.desync_udp);
}

#[test]
fn build_quic_candidates_for_suite_unknown_id_returns_error() {
    let base = minimal_ui_config();
    let result = build_quic_candidates_for_suite("nonexistent_v99", &base);
    assert!(result.is_err());
}

#[test]
fn quick_v1_suite_has_threshold_2() {
    let base = minimal_ui_config();
    let suite = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1 suite");
    assert_eq!(suite.family_failure_threshold, 2);
}

#[test]
fn full_matrix_v1_suite_has_threshold_4() {
    let base = minimal_ui_config();
    let suite = build_strategy_probe_suite("full_matrix_v1", &base).expect("full_matrix_v1 suite");
    assert_eq!(suite.family_failure_threshold, 4);
}

#[test]
fn build_quic_candidates_prioritize_active_techniques_and_keep_disabled_last() {
    let candidates = build_quic_candidates(&minimal_ui_config());
    let ids = candidates.iter().map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids.last().copied(), Some("quic_disabled"));
    assert!(ids.contains(&"quic_crypto_split"));
    assert!(ids.contains(&"quic_padding_ladder"));
    assert!(ids.contains(&"quic_version_negotiation_decoy"));
    assert!(ids.contains(&"quic_multi_initial_realistic"));
    assert!(!ids.contains(&"quic_compat_burst"));
    assert!(!ids.contains(&"quic_realistic_burst"));
    assert!(!ids.contains(&"quic_cid_churn"));
    assert!(!ids.contains(&"quic_packet_number_gap"));
}

#[test]
fn enumerate_capable_candidates_demotes_ttl_write_when_unavailable() {
    let base = minimal_ui_config();
    let all_candidates = build_tcp_candidates(&base);

    assert!(
        all_candidates.iter().any(|c| c.requires_capabilities.contains(&RuntimeCapability::TtlWrite)),
        "pool must contain at least one TtlWrite-tagged candidate"
    );
    assert!(
        all_candidates.iter().any(|c| c.requires_capabilities.is_empty()),
        "pool must contain at least one capability-free candidate"
    );

    let capable = enumerate_capable_candidates(all_candidates, &|cap| cap != RuntimeCapability::TtlWrite);

    assert!(
        !capable.iter().any(|c| c.requires_capabilities.contains(&RuntimeCapability::TtlWrite)),
        "no TtlWrite-tagged candidate should appear when TtlWrite is unavailable"
    );
    assert!(
        capable.iter().any(|c| c.requires_capabilities.is_empty()),
        "at least one capability-free candidate must survive"
    );
}

#[test]
fn ttl_dependent_candidates_tagged_with_ttl_write_capability() {
    let candidates = build_tcp_candidates(&minimal_ui_config());

    let disorder = candidates.iter().find(|c| c.id == "disorder_host").expect("disorder_host");
    assert!(disorder.requires_capabilities.contains(&RuntimeCapability::TtlWrite));

    let split = candidates.iter().find(|c| c.id == "split_host").expect("split_host");
    assert!(!split.requires_capabilities.contains(&RuntimeCapability::TtlWrite));
}

#[test]
fn primary_pool_covers_probe_matrix() {
    let base = minimal_ui_config();
    let primary = build_primary_candidates(&base);
    let quic = build_quic_candidates(&base);

    assert!(
        primary.iter().any(|c| c.family == "parser" || c.family == "parser_aggressive"),
        "HTTP bucket: primary pool must contain at least one parser evasion candidate"
    );

    assert!(
        primary.iter().any(|c| {
            matches!(
                c.id,
                "tlsrec_split_host"
                    | "split_host"
                    | "oob_host"
                    | "tlsrec_oob"
                    | "tlsrec_seqovl_midsld"
                    | "tlsrec_seqovl_sniext"
                    | "tlsrec_hostfake_split"
                    | "tlsrec_hostfake_random"
                    | "split_delayed_50ms"
                    | "split_delayed_150ms"
                    | "tlsrandrec_split"
            )
        }),
        "TLS bucket: primary pool must contain at least one TLS non-ECH candidate"
    );

    assert!(
        primary.iter().any(|c| c.eligibility == CandidateEligibility::RequiresEchCapability),
        "TLS-ECH bucket: primary pool must contain at least one ECH-eligible candidate"
    );

    assert!(
        quic.iter().any(|c| matches!(c.id, "quic_multi_initial_realistic" | "quic_sni_split" | "quic_crypto_split")),
        "QUIC bucket: quic pool must contain at least one packetizer-backed Initial layout candidate"
    );

    assert!(
        quic.iter().any(|c| matches!(c.id, "quic_version_negotiation_decoy" | "quic_fake_version")),
        "QUIC v2/vneg bucket: quic pool must contain at least one version-negotiation candidate"
    );
}

#[test]
fn primary_pool_only_keeps_ttl_write_candidates_with_graceful_fallback() {
    // The primary pool may contain TtlWrite-tagged candidates only when they
    // degrade gracefully without TTL (i.e., the hostfake split/random variants
    // still send a fake hostname even when TTL cannot be set). All other
    // strict-TTL candidates (pure fake / fakedsplit / disorder) belong in the
    // opportunistic pool. The set of allowed graceful-fallback ids is the
    // explicit allowlist below; new TTL-tagged primary candidates need an
    // explicit decision.
    const GRACEFUL_FALLBACK: &[&str] = &["baseline_current", "tlsrec_hostfake_split", "tlsrec_hostfake_random"];

    let primary = build_primary_candidates(&minimal_ui_config());
    let leaked: Vec<&str> = primary
        .iter()
        .filter(|c| c.requires_capabilities.contains(&RuntimeCapability::TtlWrite))
        .map(|c| c.id)
        .filter(|id| !GRACEFUL_FALLBACK.contains(id))
        .collect();
    assert!(
        leaked.is_empty(),
        "primary pool may only contain TtlWrite-tagged candidates from the \
         graceful-fallback allowlist; unexpected: {leaked:?}"
    );
}

#[test]
fn opportunistic_pool_contains_ttl_write_candidates() {
    let opportunistic = build_opportunistic_candidates(&minimal_ui_config());
    assert!(
        opportunistic.iter().any(|c| c.requires_capabilities.contains(&RuntimeCapability::TtlWrite)),
        "opportunistic pool must contain at least one TtlWrite-tagged candidate"
    );
}

#[test]
fn rooted_pool_contains_root_only_candidates() {
    let rooted = build_rooted_candidates(&minimal_ui_config());
    let tcp_repair_capable = probe_ip_fragmentation_capabilities().tcp_repair;
    assert_eq!(
        rooted.iter().any(|c| c.id == "fake_rst"),
        tcp_repair_capable,
        "fake_rst must appear in rooted pool iff tcp_repair is available"
    );
    assert_eq!(
        rooted.iter().any(|c| c.id == "multi_disorder"),
        tcp_repair_capable,
        "multi_disorder must appear in rooted pool iff tcp_repair is available"
    );
    let primary = build_primary_candidates(&minimal_ui_config());
    assert!(!primary.iter().any(|c| c.id == "fake_rst"), "fake_rst must not be in primary pool");
    assert!(!primary.iter().any(|c| c.id == "multi_disorder"), "multi_disorder must not be in primary pool");
}

#[test]
fn fixed_duplicate_hostfake_is_in_opportunistic_not_primary() {
    let primary = build_primary_candidates(&minimal_ui_config());
    let opportunistic = build_opportunistic_candidates(&minimal_ui_config());
    assert!(
        !primary.iter().any(|c| c.id == "tlsrec_hostfake"),
        "fixed-duplicate tlsrec_hostfake must not be in the primary pool"
    );
    assert!(
        opportunistic.iter().any(|c| c.id == "tlsrec_hostfake"),
        "fixed-duplicate tlsrec_hostfake must be in the opportunistic pool"
    );
    assert!(
        primary.iter().any(|c| c.id == "tlsrec_hostfake_split"),
        "tlsrec_hostfake_split must remain in the primary pool"
    );
    assert!(
        primary.iter().any(|c| c.id == "tlsrec_hostfake_random"),
        "tlsrec_hostfake_random must remain in the primary pool"
    );
}

#[test]
fn full_tcp_candidates_is_superset_of_primary_and_opportunistic() {
    let base = minimal_ui_config();
    let full = build_tcp_candidates(&base);
    let primary = build_primary_candidates(&base);
    let opportunistic = build_opportunistic_candidates(&base);

    for c in primary.iter().chain(opportunistic.iter()) {
        assert!(
            full.iter().any(|f| f.id == c.id),
            "candidate '{}' from primary/opportunistic is missing from full set",
            c.id
        );
    }
    assert!(full.len() >= primary.len() + opportunistic.len());
}
