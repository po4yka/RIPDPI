//! Plan-parity drift tests — old config fixtures must produce identical plans.
//!
//! The descriptor/factory refactor replaced the `BUILTIN_TECHNIQUES` table and
//! the `BuiltinTechnique::plan` central `match` with concrete registered
//! strategies. These tests pin that the on-wire desync behavior is unchanged:
//! each pre-refactor step kind plans exactly the `DesyncAction` its old
//! `BuiltinTechnique::plan` arm pushed, legacy `type:` aliases still parse, and
//! an unknown kind parses cleanly but fails at registry resolution.

use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::{StrategyRegistry, StrategyRegistryError};
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    RuntimeCapability, StrategyContext, StrategyVerdict,
};

/// A context whose capabilities satisfy every built-in technique's tier gate.
fn tier3_all_caps() -> Capabilities {
    Capabilities {
        tier: CapabilityTier::Tier3,
        available: vec![
            RuntimeCapability::TtlWrite,
            RuntimeCapability::RawTcpFakeSend,
            RuntimeCapability::RawUdpFragmentation,
            RuntimeCapability::ReplacementSocket,
            RuntimeCapability::VpnProtect,
            RuntimeCapability::VpnMode,
            RuntimeCapability::TcpWindowClamp,
        ],
    }
}

/// Materializes a one-step strategy config and executes it against `caps`.
fn execute_single_step(step_type: &str, caps: &Capabilities) -> (StrategyVerdict, DesyncPlan) {
    let yaml = format!("version: 1\nstrategies:\n  - id: parity\n    steps:\n      - type: {step_type}\n");
    let config = parse_yaml_str(&yaml, ".").expect("strategy config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("strategy config should materialize");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps,
        flow_id: FlowId(1),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();
    let verdict = registry.execute(&ctx, &mut plan);
    (verdict, plan)
}

#[test]
fn core_techniques_plan_their_pre_refactor_actions() {
    let caps = tier3_all_caps();
    let cases: &[(&str, DesyncAction)] = &[
        ("split", DesyncAction::Split { offset: 0, disorder: false }),
        ("disorder", DesyncAction::Split { offset: 0, disorder: true }),
        ("fake", DesyncAction::WriteFake { ttl: None, sni_mode: None, payload_file: None }),
        ("oob", DesyncAction::WriteUrgent { prefix: Vec::new(), urgent_byte: 0 }),
        ("fake_rst", DesyncAction::SendFakeRst { ttl: None }),
        ("seq_overlap", DesyncAction::Write(Vec::new())),
        ("ip_frag", DesyncAction::RawSend(Vec::new())),
        ("multi_disorder", DesyncAction::RawSend(Vec::new())),
        ("tls_rec", DesyncAction::Write(Vec::new())),
        ("tls_rand_rec", DesyncAction::Write(Vec::new())),
    ];
    for (step, action) in cases {
        let (verdict, plan) = execute_single_step(step, &caps);
        assert_eq!(verdict, StrategyVerdict::Apply, "`{step}` must apply");
        assert_eq!(plan.actions, std::slice::from_ref(action), "`{step}` planned the wrong action");
    }
}

#[test]
fn unimplemented_synack_steps_fall_back_without_actions() {
    let caps = tier3_all_caps();
    for step in ["synack", "synack_split"] {
        let (verdict, plan) = execute_single_step(step, &caps);
        assert_eq!(
            verdict,
            StrategyVerdict::FallbackPlain,
            "`{step}` is registered but unimplemented — its plan must fail and the chain fall back",
        );
        assert!(plan.actions.is_empty(), "`{step}` must not plan any action");
    }
}

#[test]
fn tier_gated_step_is_skipped_below_its_required_tier() {
    // `disorder` requires Tier2; a Tier0 context must skip it entirely.
    let caps = Capabilities { tier: CapabilityTier::Tier0, available: Vec::new() };
    let (verdict, plan) = execute_single_step("disorder", &caps);
    assert_eq!(verdict, StrategyVerdict::FallbackPlain);
    assert!(plan.actions.is_empty());
}

#[test]
fn multi_step_chain_runs_the_first_matching_step() {
    // `execute` stops at the first matching strategy that plans successfully —
    // the chain behavior is unchanged by the registry refactor.
    let caps = tier3_all_caps();
    let yaml = "version: 1\nstrategies:\n  - id: tls_chain\n    match:\n      proto: [tls]\n    steps:\n      - type: split\n      - type: fake\n";
    let config = parse_yaml_str(yaml, ".").expect("strategy config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("strategy config should materialize");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(2),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();
    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::Apply);
    assert_eq!(plan.actions, [DesyncAction::Split { offset: 0, disorder: false }]);
}

#[test]
fn legacy_snake_and_camel_aliases_materialize_to_canonical_ids() {
    // Every pre-refactor `#[serde(alias = …)]` / `#[serde(rename = …)]`
    // spelling must still parse to the same canonical registry id.
    let yaml = "version: 1\nstrategies:\n  - id: aliases\n    steps:\n      - type: tlsRec\n      - type: tls_rec\n      - type: httpDomcase\n      - type: http_domcase\n      - type: fakeRst\n      - type: fake_rst\n      - type: ipv6Ext\n      - type: ipv6_ext\n      - type: seqOverlap\n      - type: multiDisorder\n";
    let config = parse_yaml_str(yaml, ".").expect("legacy aliases must still parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("strategy config should materialize");
    let ids: Vec<&str> = registry.list().map(|descriptor| descriptor.id.as_str()).collect();
    assert_eq!(
        ids,
        [
            "tls_rec",
            "tls_rec",
            "http_domcase",
            "http_domcase",
            "fake_rst",
            "fake_rst",
            "ipv6_ext",
            "ipv6_ext",
            "seq_overlap",
            "multi_disorder",
        ],
    );
}

#[test]
fn unknown_step_type_parses_then_fails_at_registry_resolution() {
    let yaml = "version: 1\nstrategies:\n  - id: experimental\n    steps:\n      - type: experimental_xyz\n";
    // Parsing succeeds — the unknown id is carried as `StepType::Unknown`
    // instead of failing serde enum decoding.
    let config = parse_yaml_str(yaml, ".").expect("an unknown step type must still parse");
    // Resolution then fails cleanly with `UnknownType`.
    let error = StrategyRegistry::from_loaded_config(&config)
        .err()
        .expect("an unknown step type must fail at registry resolution");
    assert!(
        matches!(&error, StrategyRegistryError::UnknownType(id) if id == "experimental_xyz"),
        "expected UnknownType(experimental_xyz), got {error:?}",
    );
}
