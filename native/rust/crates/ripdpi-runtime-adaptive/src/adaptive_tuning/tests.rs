use super::AdaptivePlannerResolver;
use super::feedback::*;
use super::key::*;
use super::persistence::*;
use super::state::*;
use std::{fs, net::SocketAddr};

use ripdpi_config::{
    DesyncGroup, OffsetBase, OffsetExpr, QuicFakeProfile, TcpChainStep, TcpChainStepKind, TcpTlsRandRecPayload,
    UdpChainStep, UdpChainStepKind,
};
use ripdpi_desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_packets::{DEFAULT_FAKE_TLS, QUIC_V2_VERSION, build_realistic_quic_initial};

fn addr(port: u16) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], port))
}

fn config_with_adaptive_store(groups: Vec<DesyncGroup>) -> (ripdpi_config::RuntimeConfig, tempfile::TempDir) {
    let tmp_dir = tempfile::tempdir().expect("create temp dir for adaptive store test");
    let mut config = ripdpi_config::RuntimeConfig { groups, ..ripdpi_config::RuntimeConfig::default() };
    config.host_autolearn.store_path = Some(tmp_dir.path().join("host-autolearn.json").to_string_lossy().into_owned());
    (config, tmp_dir)
}

#[test]
fn tcp_failure_rotates_one_adaptive_dimension_at_a_time() {
    let payload = b"GET / HTTP/1.1\r\nHost: video.example.test\r\n\r\n";
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
        TcpChainStep::new(TcpChainStepKind::TlsRandRec, OffsetExpr::adaptive(OffsetBase::AutoSniExt))
            .with_tls_randrec_payload(TcpTlsRandRecPayload {
                fragment_count: 3,
                min_fragment_size: 12,
                max_fragment_size: 64,
            }),
    ];

    let mut resolver = AdaptivePlannerResolver::default();
    let target = addr(443);
    let first = resolver.resolve_tcp_hints(None, 0, target, Some("Video.Example.Test"), &group, payload);
    assert_eq!(first.split_offset_base, Some(OffsetBase::Host));
    assert_eq!(first.tls_record_offset_base, Some(OffsetBase::SniExt));
    assert_eq!(first.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Balanced));

    resolver.note_tcp_failure(None, 0, target, Some("video.example.test"), payload);
    let second = resolver.resolve_tcp_hints(None, 0, target, Some("video.example.test"), &group, payload);
    let second_changes = [
        first.split_offset_base != second.split_offset_base,
        first.tls_record_offset_base != second.tls_record_offset_base,
        first.tlsrandrec_profile != second.tlsrandrec_profile,
    ];
    assert_eq!(second_changes.into_iter().filter(|changed| *changed).count(), 1);

    resolver.note_tcp_failure(None, 0, target, Some("video.example.test"), payload);
    let third = resolver.resolve_tcp_hints(None, 0, target, Some("video.example.test"), &group, payload);
    let third_changes = [
        second.split_offset_base != third.split_offset_base,
        second.tls_record_offset_base != third.tls_record_offset_base,
        second.tlsrandrec_profile != third.tlsrandrec_profile,
    ];
    assert_eq!(third_changes.into_iter().filter(|changed| *changed).count(), 1);
}

#[test]
fn tcp_success_pins_current_candidate_until_next_failure() {
    let payload = b"GET / HTTP/1.1\r\nHost: docs.example.test\r\n\r\n";
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

    let mut resolver = AdaptivePlannerResolver::default();
    let target = addr(80);
    let first = resolver.resolve_tcp_hints(None, 0, target, Some("docs.example.test"), &group, payload);
    assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

    resolver.note_tcp_failure(None, 0, target, Some("docs.example.test"), payload);
    let advanced = resolver.resolve_tcp_hints(None, 0, target, Some("docs.example.test"), &group, payload);
    assert_eq!(advanced.split_offset_base, Some(OffsetBase::MidSld));

    resolver.note_tcp_success(None, 0, target, Some("docs.example.test"), payload);
    resolver.note_tcp_failure(None, 0, target, Some("docs.example.test"), payload);
    let next = resolver.resolve_tcp_hints(None, 0, target, Some("docs.example.test"), &group, payload);
    assert_eq!(next.split_offset_base, Some(OffsetBase::EndHost));
}

#[test]
fn udp_feedback_is_scoped_by_host_and_quic_profile() {
    let payload =
        build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("quic initial payload");
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 2,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];

    let mut resolver = AdaptivePlannerResolver::default();
    let target = addr(443);
    let first = resolver.resolve_udp_hints(None, 0, target, Some("media.example.test"), &group, &payload);
    assert_eq!(first.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Balanced));
    assert_eq!(first.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));

    resolver.note_udp_failure(None, 0, target, Some("media.example.test"), &payload);
    let second = resolver.resolve_udp_hints(None, 0, target, Some("media.example.test"), &group, &payload);
    assert_eq!(second.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
    assert_eq!(second.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));

    resolver.note_udp_failure(None, 0, target, Some("media.example.test"), &payload);
    let third = resolver.resolve_udp_hints(None, 0, target, Some("media.example.test"), &group, &payload);
    assert_eq!(third.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
    assert_eq!(third.quic_fake_profile, Some(QuicFakeProfile::CompatDefault));

    let isolated = resolver.resolve_udp_hints(None, 0, target, Some("other.example.test"), &group, &payload);
    assert_eq!(isolated.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Balanced));
    assert_eq!(isolated.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
}

#[test]
fn tcp_feedback_is_scoped_by_network_scope_key() {
    let payload = b"GET / HTTP/1.1\r\nHost: video.example.test\r\n\r\n";
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

    let mut resolver = AdaptivePlannerResolver::default();
    let target = addr(443);

    let baseline = resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("video.example.test"), &group, payload);
    resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("video.example.test"), payload);
    let advanced = resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("video.example.test"), &group, payload);
    let isolated = resolver.resolve_tcp_hints(Some("scope-b"), 0, target, Some("video.example.test"), &group, payload);

    assert_ne!(baseline.split_offset_base, advanced.split_offset_base);
    assert_eq!(isolated.split_offset_base, baseline.split_offset_base);
}

#[test]
fn adaptive_dimension_order_is_stable_within_same_scope() {
    let payload = b"GET / HTTP/1.1\r\nHost: docs.example.test\r\n\r\n";
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
        TcpChainStep::new(TcpChainStepKind::TlsRandRec, OffsetExpr::adaptive(OffsetBase::AutoSniExt))
            .with_tls_randrec_payload(TcpTlsRandRecPayload {
                fragment_count: 3,
                min_fragment_size: 12,
                max_fragment_size: 64,
            }),
    ];
    let target = addr(443);

    let mut first = AdaptivePlannerResolver::default();
    let base_first = first.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);
    first.note_tcp_failure(Some("scope-a"), 0, target, Some("docs.example.test"), payload);
    let next_first = first.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);

    let mut second = AdaptivePlannerResolver::default();
    let base_second = second.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);
    second.note_tcp_failure(Some("scope-a"), 0, target, Some("docs.example.test"), payload);
    let next_second = second.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);

    assert_eq!(base_first, base_second);
    assert_eq!(next_first, next_second);
}

// --- ChoiceState unit tests ---

#[test]
fn choice_state_pins_on_success() {
    let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
    assert_eq!(cs.current(), Some(10));

    cs.note_success();
    assert_eq!(cs.current(), Some(10));
    assert_eq!(cs.pinned, Some(10));

    // Even after advancing the index, pinned value still wins.
    cs.candidate_index = 2;
    assert_eq!(cs.current(), Some(10));
}

#[test]
fn choice_state_advances_on_failure() {
    let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
    assert_eq!(cs.current(), Some(10));

    cs.note_failure(1000);
    assert_eq!(cs.current(), Some(20));
    assert_eq!(cs.candidate_index, 1);
}

#[test]
fn choice_state_cooldown_skips_recent_failure() {
    let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
    let t = 100_000u64;

    // Fail index 0 at time T -- puts it on cooldown, advances to index 1.
    cs.note_failure(t);
    assert_eq!(cs.current(), Some(20));

    // Fail index 1 at time T+1 (within 15s window of index 0).
    // Index 0 is still on cooldown, so should skip to index 2.
    cs.note_failure(t + 1);
    assert_eq!(cs.current(), Some(30));
    assert_eq!(cs.candidate_index, 2);
}

#[test]
fn choice_state_cooldown_expires() {
    let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
    let t = 100_000u64;

    // Fail index 0 at time T -- cooldown until T+15000, advances to 1.
    cs.note_failure(t);
    assert_eq!(cs.current(), Some(20));

    // Fail index 1 at T+1 -- cooldown until T+15001, advances to 2
    // (index 0 still on cooldown).
    cs.note_failure(t + 1);
    assert_eq!(cs.current(), Some(30));

    // Fail index 2 after index 0's cooldown has expired (T+16000 > T+15000).
    // Index 0 is now eligible again.
    cs.note_failure(t + 16_000);
    assert_eq!(cs.current(), Some(10));
    assert_eq!(cs.candidate_index, 0);
}

#[test]
fn single_candidate_failure_is_noop() {
    let mut cs = ChoiceState::new(vec![42u32]);
    assert_eq!(cs.current(), Some(42));

    cs.note_failure(1000);
    assert_eq!(cs.current(), Some(42));
    assert_eq!(cs.candidate_index, 0);
}

// --- AdaptivePlannerState unit tests ---

/// Helper: build a DesyncGroup with a single adaptive TCP split step.
fn tcp_group_with_adaptive_split() -> DesyncGroup {
    let mut g = DesyncGroup::new(0);
    g.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];
    g
}

#[test]
fn planner_state_cycles_dimensions_on_failure() {
    let seed = 12345u64;
    let mut state = AdaptivePlannerState::new(seed);

    // Sync TCP candidates with a non-TLS payload so split_offset_base is populated.
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    state.sync_tcp_candidates(&group, payload);

    // The only adaptive dimension for this config is split_offset_base (dimension 0).
    // Record initial value.
    let initial = state.current_hints().split_offset_base;
    assert!(initial.is_some());

    // After note_failure, the value for split_offset_base should change
    // (the dimension cursor advances to the next dimension, but only
    // dimension 0 is active, so it will eventually circle back to it).
    let now = 100_000u64;
    // We call advance_dimension directly to verify dimension cycling with
    // a known order.
    let order = state.dimension_order.clone();
    let first_active = order.iter().position(|&d| d == 0).expect("dimension 0 in order");

    // Manually advance so we can control timing (note_failure uses now_millis()).
    state.dimension_cursor = first_active;
    let advanced = state.advance_dimension(0, now);
    assert!(advanced);

    let after = state.current_hints().split_offset_base;
    assert_ne!(initial, after, "split_offset_base should change after advancing dimension 0");
}

#[test]
fn planner_state_success_pins_all_dimensions() {
    let mut state = AdaptivePlannerState::new(99);
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    state.sync_tcp_candidates(&group, payload);

    let before = state.current_hints();
    state.note_success();

    // All dimensions should be pinned -- verify via the underlying state.
    assert!(state.split_offset_base.as_ref().unwrap().pinned.is_some());

    // Re-resolve hints: should match what was pinned.
    let after = state.current_hints();
    assert_eq!(before.split_offset_base, after.split_offset_base);
}

// --- AdaptivePlannerResolver tests ---

#[test]
fn resolver_tracks_per_host_state() {
    let payload = b"GET / HTTP/1.1\r\nHost: alpha.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let target = addr(80);

    let mut resolver = AdaptivePlannerResolver::default();

    let alpha = resolver.resolve_tcp_hints(None, 0, target, Some("alpha.test"), &group, payload);
    let beta = resolver.resolve_tcp_hints(None, 0, target, Some("beta.test"), &group, payload);

    // Both should start with the same initial candidate (Host).
    assert_eq!(alpha.split_offset_base, Some(OffsetBase::Host));
    assert_eq!(beta.split_offset_base, Some(OffsetBase::Host));

    // Fail alpha, advance it.
    resolver.note_tcp_failure(None, 0, target, Some("alpha.test"), payload);
    let alpha_after = resolver.resolve_tcp_hints(None, 0, target, Some("alpha.test"), &group, payload);
    let beta_after = resolver.resolve_tcp_hints(None, 0, target, Some("beta.test"), &group, payload);

    // Alpha should have advanced, beta should remain unchanged.
    assert_ne!(alpha_after.split_offset_base, Some(OffsetBase::Host));
    assert_eq!(beta_after.split_offset_base, Some(OffsetBase::Host));
}

#[test]
fn resolver_tcp_success_pins_state() {
    let payload = b"GET / HTTP/1.1\r\nHost: pin.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let target = addr(80);

    let mut resolver = AdaptivePlannerResolver::default();

    let first = resolver.resolve_tcp_hints(None, 0, target, Some("pin.test"), &group, payload);
    assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

    // Pin via success.
    resolver.note_tcp_success(None, 0, target, Some("pin.test"), payload);

    // Resolve again -- should still be Host (pinned).
    let after_pin = resolver.resolve_tcp_hints(None, 0, target, Some("pin.test"), &group, payload);
    assert_eq!(after_pin.split_offset_base, Some(OffsetBase::Host));

    // Even after a failure, the pin is cleared and we advance, but the key
    // point is the pin held across the second resolve.
    resolver.note_tcp_failure(None, 0, target, Some("pin.test"), payload);
    let after_fail = resolver.resolve_tcp_hints(None, 0, target, Some("pin.test"), &group, payload);
    assert_ne!(after_fail.split_offset_base, first.split_offset_base);
}

// --- Tests requested for full coverage ---

#[test]
fn resolver_returns_default_hints_for_fresh_key() {
    let payload = DEFAULT_FAKE_TLS;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoBalanced))];

    let mut resolver = AdaptivePlannerResolver::default();
    let hints = resolver.resolve_tcp_hints(None, 0, addr(443), Some("fresh.test"), &group, payload);

    // A fresh key should return index-0 candidates. For AutoBalanced on a
    // TLS payload, index 0 is ExtLen.
    assert_eq!(hints.split_offset_base, Some(OffsetBase::ExtLen));
    // No UDP-related hints on a TCP resolve.
    assert_eq!(hints.udp_burst_profile, None);
    assert_eq!(hints.quic_fake_profile, None);
}

#[test]
fn note_success_pins_current_candidate() {
    let payload = b"GET / HTTP/1.1\r\nHost: pin-check.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let target = addr(80);

    let mut resolver = AdaptivePlannerResolver::default();
    let first = resolver.resolve_tcp_hints(None, 0, target, Some("pin-check.test"), &group, payload);
    assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

    resolver.note_tcp_success(None, 0, target, Some("pin-check.test"), payload);

    // Subsequent resolves must return the same pinned value.
    let second = resolver.resolve_tcp_hints(None, 0, target, Some("pin-check.test"), &group, payload);
    assert_eq!(second.split_offset_base, first.split_offset_base);

    let third = resolver.resolve_tcp_hints(None, 0, target, Some("pin-check.test"), &group, payload);
    assert_eq!(third.split_offset_base, first.split_offset_base);
}

#[test]
fn note_failure_advances_to_next_candidate() {
    let payload = b"GET / HTTP/1.1\r\nHost: advance.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let target = addr(80);

    let mut resolver = AdaptivePlannerResolver::default();
    let first = resolver.resolve_tcp_hints(None, 0, target, Some("advance.test"), &group, payload);
    assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

    resolver.note_tcp_failure(None, 0, target, Some("advance.test"), payload);
    let second = resolver.resolve_tcp_hints(None, 0, target, Some("advance.test"), &group, payload);

    // At least one dimension must have changed after failure.
    assert_ne!(first.split_offset_base, second.split_offset_base, "split_offset_base should differ after failure");
}

#[test]
fn choice_state_new_starts_at_index_zero() {
    let cs = ChoiceState::new(vec![100u32, 200, 300]);
    assert_eq!(cs.candidate_index, 0);
    assert_eq!(cs.pinned, None);
    assert_eq!(cs.current(), Some(100));
}

#[test]
fn choice_state_pin_preserves_current_value() {
    let mut cs = ChoiceState::new(vec![5u32, 10, 15]);
    // Advance to index 1 via failure.
    cs.note_failure(1000);
    assert_eq!(cs.current(), Some(10));

    cs.note_success();
    assert_eq!(cs.pinned, Some(10));
    assert_eq!(cs.current(), Some(10));

    // Manually move candidate_index -- pinned value should still win.
    cs.candidate_index = 2;
    assert_eq!(cs.current(), Some(10));
}

#[test]
fn choice_state_advance_wraps_around() {
    let mut cs = ChoiceState::new(vec![1u32, 2, 3]);
    assert_eq!(cs.candidate_index, 0);

    // Advance through all candidates using well-spaced timestamps to avoid
    // cooldown interference.
    cs.note_failure(0);
    assert_eq!(cs.candidate_index, 1);

    cs.note_failure(ADAPTIVE_RETRY_WINDOW_MS + 1);
    assert_eq!(cs.candidate_index, 2);

    cs.note_failure(2 * ADAPTIVE_RETRY_WINDOW_MS + 2);
    assert_eq!(cs.candidate_index, 0, "should wrap around to index 0");
    assert_eq!(cs.current(), Some(1));
}

#[test]
fn adaptive_store_round_trips_full_state() {
    let payload = b"GET / HTTP/1.1\r\nHost: persist.example.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let (config, _tmp) = config_with_adaptive_store(vec![group.clone()]);
    let target = addr(443);

    let mut resolver = AdaptivePlannerResolver::default();
    resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("persist.example.test"), &group, payload);
    resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("persist.example.test"), payload);
    resolver.note_tcp_success(Some("scope-a"), 0, target, Some("persist.example.test"), payload);
    resolver.flush_store(&config);

    let reloaded = AdaptivePlannerResolver::load(&config);
    assert_eq!(reloaded.states, resolver.states);
}

#[test]
fn adaptive_store_fingerprint_invalidates_stale_entries() {
    let payload = b"GET / HTTP/1.1\r\nHost: fingerprint.example.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let (config, _tmp) = config_with_adaptive_store(vec![group.clone()]);
    let store_path = adaptive_store_path(&config).expect("test config has store_path");
    let target = addr(443);

    let mut resolver = AdaptivePlannerResolver::default();
    resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("fingerprint.example.test"), &group, payload);
    resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("fingerprint.example.test"), payload);
    resolver.flush_store(&config);

    let mut changed_group = group.clone();
    changed_group
        .actions
        .tcp_chain
        .push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoEndHost)));
    let (changed_config, _changed_tmp) = config_with_adaptive_store(vec![changed_group]);
    let changed_store_path = adaptive_store_path(&changed_config).expect("test config has store_path");
    assert!(store_path.exists(), "flush should write adaptive store before reload test");
    fs::copy(&store_path, &changed_store_path).expect("copy persisted store");

    let reloaded = AdaptivePlannerResolver::load(&changed_config);
    assert!(reloaded.states.is_empty(), "changed group layout should invalidate persisted adaptive state");
}

#[test]
fn adaptive_store_debounce_defers_write_until_flush() {
    let payload = b"GET / HTTP/1.1\r\nHost: debounce.example.test\r\n\r\n";
    let group = tcp_group_with_adaptive_split();
    let (config, _tmp) = config_with_adaptive_store(vec![group.clone()]);
    let store_path = adaptive_store_path(&config).expect("test config has store_path");
    let target = addr(443);

    let mut resolver = AdaptivePlannerResolver::default();
    resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("debounce.example.test"), &group, payload);
    resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("debounce.example.test"), payload);
    resolver.last_persist_at_ms = now_millis();
    resolver.persist_if_due(&config);
    assert!(!store_path.exists(), "debounced persist should not write immediately");

    resolver.flush_store(&config);
    assert!(store_path.exists(), "flush should force adaptive store write");
}

#[test]
fn adaptive_store_returns_none_when_host_store_path_is_missing() {
    let config = ripdpi_config::RuntimeConfig::default();
    assert_eq!(adaptive_store_path(&config), None);
}

#[test]
fn stored_offset_base_round_trips_ech_ext() {
    let stored = StoredOffsetBase::from(OffsetBase::EchExt);

    assert_eq!(stored, StoredOffsetBase::EchExt);
    assert_eq!(restore_offset_base(stored), Some(OffsetBase::EchExt));
}
