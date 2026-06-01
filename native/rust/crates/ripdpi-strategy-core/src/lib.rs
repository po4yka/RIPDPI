//! Core RIPDPI desync techniques — the `DesyncStrategy` implementations for
//! the stateless built-in techniques, plus the descriptor-only `synack` /
//! `synack_split` placeholders.
//!
//! Each technique contributes one [`StrategyStepRegistration`] to the
//! [`ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS`] slice, so
//! `ripdpi-strategy-registry` resolves it from its descriptor with no central
//! match arm. This crate replaces the registry's former `BUILTIN_TECHNIQUES`
//! table and `BuiltinTechnique::plan` central match — adding a core technique
//! is one `core_strategy!` invocation here, not a parallel edit to a
//! definition table and an action match.
//!
//! `synack` / `synack_split` are *registered but unimplemented* in the strategy
//! registry: they carry descriptors for inventory/capability surfaces, but the
//! registry materializes placeholders whose `plan` fails. The TUN ingress
//! interceptor consumes the same YAML step ids separately for SYN-ACK packet
//! injection.

#![forbid(unsafe_code)]

use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, RuntimeCapability, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyStepDescriptor, StrategyStepFactory, StrategyStepRegistration,
};

/// Empty capability set — a technique that runs on any device.
const NO_CAPS: &[RuntimeCapability] = &[];
/// Capabilities required by the TCP-replacement-socket techniques.
const TCP_REPAIR_CAPS: &[RuntimeCapability] = &[RuntimeCapability::ReplacementSocket];
/// Capabilities required by the VPN/TUN packet-path techniques.
const VPN_CAPS: &[RuntimeCapability] = &[RuntimeCapability::VpnMode];
/// Capabilities required by the SYN-ACK techniques.
const SYNACK_CAPS: &[RuntimeCapability] =
    &[RuntimeCapability::VpnMode, RuntimeCapability::RawTcpFakeSend, RuntimeCapability::VpnProtect];

/// Defines one stateless core technique: a zero-sized [`DesyncStrategy`] whose
/// `plan` appends a fixed [`DesyncAction`], a `make` builder, and the
/// [`StrategyStepRegistration`] linking its descriptor to that builder.
///
/// `matches` is a coarse tier gate only — `ctx.caps.tier >= required_tier` —
/// reproducing the former `BuiltinTechnique::matches` behavior exactly (it
/// never inspected the fine-grained capability set).
macro_rules! core_strategy {
    (
        $strategy:ident, $make:ident, $registration:ident,
        id = $id:literal, label = $label:literal,
        tier = $tier:expr_2021, caps = $caps:expr_2021,
        action = $action:expr_2021 $(,)?
    ) => {
        #[doc = concat!("The `", $id, "` core desync technique.")]
        #[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
        pub struct $strategy;

        impl $strategy {
            /// The static step-kind descriptor for this technique.
            pub const DESCRIPTOR: StrategyStepDescriptor = StrategyStepDescriptor {
                id: $id,
                label: $label,
                aliases: &[],
                required_tier: $tier,
                required_capabilities: $caps,
                needs_parameters: false,
                parameter_schema: "",
            };
        }

        impl DesyncStrategy for $strategy {
            fn id(&self) -> &str {
                $id
            }

            fn matches(&self, ctx: &StrategyContext<'_>) -> bool {
                ctx.caps.tier >= Self::DESCRIPTOR.required_tier
            }

            fn plan(&self, _ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
                plan.actions.push($action);
                Ok(())
            }

            fn describe(&self) -> StrategyDescriptor {
                StrategyDescriptor {
                    id: $id.to_owned(),
                    label: $label.to_owned(),
                    required_tier: $tier,
                    required_capabilities: $caps.to_vec(),
                    ..StrategyDescriptor::default()
                }
            }
        }

        fn $make() -> Box<dyn DesyncStrategy> {
            Box::new($strategy)
        }

        #[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
        static $registration: StrategyStepRegistration = StrategyStepRegistration {
            descriptor: $strategy::DESCRIPTOR,
            factory: StrategyStepFactory::Stateless($make),
        };
    };
}

core_strategy!(
    SplitStrategy,
    make_split,
    SPLIT_REGISTRATION,
    id = "split",
    label = "Split",
    tier = CapabilityTier::Tier0,
    caps = NO_CAPS,
    action = DesyncAction::Split { offset: 0, disorder: false },
);

core_strategy!(
    DisorderStrategy,
    make_disorder,
    DISORDER_REGISTRATION,
    id = "disorder",
    label = "Disorder",
    tier = CapabilityTier::Tier2,
    caps = TCP_REPAIR_CAPS,
    action = DesyncAction::Split { offset: 0, disorder: true },
);

core_strategy!(
    FakeStrategy,
    make_fake,
    FAKE_REGISTRATION,
    id = "fake",
    label = "Fake packet",
    tier = CapabilityTier::Tier0,
    caps = NO_CAPS,
    action = DesyncAction::WriteFake { ttl: None, sni_mode: None, payload_file: None },
);

core_strategy!(
    OobStrategy,
    make_oob,
    OOB_REGISTRATION,
    id = "oob",
    label = "TCP urgent/OOB",
    tier = CapabilityTier::Tier1,
    caps = NO_CAPS,
    action = DesyncAction::WriteUrgent { prefix: Vec::new(), urgent_byte: 0 },
);

core_strategy!(
    FakeRstStrategy,
    make_fake_rst,
    FAKE_RST_REGISTRATION,
    id = "fake_rst",
    label = "Fake RST",
    tier = CapabilityTier::Tier2,
    caps = TCP_REPAIR_CAPS,
    action = DesyncAction::SendFakeRst { ttl: None },
);

core_strategy!(
    SeqOverlapStrategy,
    make_seq_overlap,
    SEQ_OVERLAP_REGISTRATION,
    id = "seq_overlap",
    label = "Sequence overlap",
    tier = CapabilityTier::Tier2,
    caps = TCP_REPAIR_CAPS,
    action = DesyncAction::Write(Vec::new()),
);

core_strategy!(
    IpFragStrategy,
    make_ip_frag,
    IP_FRAG_REGISTRATION,
    id = "ip_frag",
    label = "IP fragmentation",
    tier = CapabilityTier::Tier3,
    caps = VPN_CAPS,
    action = DesyncAction::RawSend(Vec::new()),
);

core_strategy!(
    MultiDisorderStrategy,
    make_multi_disorder,
    MULTI_DISORDER_REGISTRATION,
    id = "multi_disorder",
    label = "Multi-disorder",
    tier = CapabilityTier::Tier2,
    caps = TCP_REPAIR_CAPS,
    action = DesyncAction::RawSend(Vec::new()),
);

core_strategy!(
    TlsRecStrategy,
    make_tls_rec,
    TLS_REC_REGISTRATION,
    id = "tls_rec",
    label = "TLS record split",
    tier = CapabilityTier::Tier0,
    caps = NO_CAPS,
    action = DesyncAction::Write(Vec::new()),
);

core_strategy!(
    TlsRandRecStrategy,
    make_tls_rand_rec,
    TLS_RAND_REC_REGISTRATION,
    id = "tls_rand_rec",
    label = "TLS random records",
    tier = CapabilityTier::Tier0,
    caps = NO_CAPS,
    action = DesyncAction::Write(Vec::new()),
);

/// `synack` — SYN-ACK low-TTL technique. Registered with a descriptor for
/// inventory and capability surfaces; the strategy registry does not produce a
/// `DesyncAction` for it. The TUN ingress interceptor consumes the YAML step id
/// separately.
#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static SYNACK_REGISTRATION: StrategyStepRegistration = StrategyStepRegistration {
    descriptor: StrategyStepDescriptor {
        id: "synack",
        label: "SYN-ACK low TTL",
        aliases: &[],
        required_tier: CapabilityTier::Tier3,
        required_capabilities: SYNACK_CAPS,
        needs_parameters: false,
        parameter_schema: "",
    },
    factory: StrategyStepFactory::Unimplemented,
};

/// `synack_split` — SYN-ACK split technique. Registered descriptor-only for the
/// same reason as [`SYNACK_REGISTRATION`].
#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static SYNACK_SPLIT_REGISTRATION: StrategyStepRegistration = StrategyStepRegistration {
    descriptor: StrategyStepDescriptor {
        id: "synack_split",
        label: "SYN-ACK split",
        aliases: &[],
        required_tier: CapabilityTier::Tier3,
        required_capabilities: SYNACK_CAPS,
        needs_parameters: false,
        parameter_schema: "",
    },
    factory: StrategyStepFactory::Unimplemented,
};

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_strategy_trait::{Capabilities, ConnectionState, Dissect, FlowDirection, FlowId};

    /// Runs a strategy at `tier` against a default context, returning whether
    /// it matched and the plan it produced.
    fn run(strategy: &dyn DesyncStrategy, tier: CapabilityTier) -> (bool, DesyncPlan) {
        let dissect = Dissect::default();
        let conn = ConnectionState::default();
        let caps = Capabilities { tier, available: Vec::new() };
        let ctx = StrategyContext {
            dissect: &dissect,
            conn: &conn,
            caps: &caps,
            flow_id: FlowId(1),
            payload: b"payload",
            direction: FlowDirection::Outbound,
        };
        let matched = strategy.matches(&ctx);
        let mut plan = DesyncPlan::default();
        strategy.plan(&ctx, &mut plan).expect("core strategy plan never fails");
        (matched, plan)
    }

    #[test]
    fn each_strategy_describe_agrees_with_its_descriptor() {
        let strategies: &[(&dyn DesyncStrategy, StrategyStepDescriptor)] = &[
            (&SplitStrategy, SplitStrategy::DESCRIPTOR),
            (&DisorderStrategy, DisorderStrategy::DESCRIPTOR),
            (&FakeStrategy, FakeStrategy::DESCRIPTOR),
            (&OobStrategy, OobStrategy::DESCRIPTOR),
            (&FakeRstStrategy, FakeRstStrategy::DESCRIPTOR),
            (&SeqOverlapStrategy, SeqOverlapStrategy::DESCRIPTOR),
            (&IpFragStrategy, IpFragStrategy::DESCRIPTOR),
            (&MultiDisorderStrategy, MultiDisorderStrategy::DESCRIPTOR),
            (&TlsRecStrategy, TlsRecStrategy::DESCRIPTOR),
            (&TlsRandRecStrategy, TlsRandRecStrategy::DESCRIPTOR),
        ];
        for (strategy, descriptor) in strategies {
            let described = strategy.describe();
            assert_eq!(described.id, descriptor.id);
            assert_eq!(described.label, descriptor.label);
            assert_eq!(described.required_tier, descriptor.required_tier);
            assert_eq!(described.required_capabilities, descriptor.required_capabilities);
        }
    }

    #[test]
    fn split_and_disorder_plan_offset_zero_actions() {
        let (matched, plan) = run(&SplitStrategy, CapabilityTier::Tier0);
        assert!(matched, "split is Tier0 and always matches");
        assert_eq!(plan.actions, [DesyncAction::Split { offset: 0, disorder: false }]);

        let (matched, plan) = run(&DisorderStrategy, CapabilityTier::Tier2);
        assert!(matched);
        assert_eq!(plan.actions, [DesyncAction::Split { offset: 0, disorder: true }]);
    }

    #[test]
    fn tier_gate_skips_strategy_without_required_tier() {
        // The tier check lives in `matches`, not `plan` — the registry's
        // `execute` is what skips `plan` for an unmatched strategy.
        let (matched, _) = run(&DisorderStrategy, CapabilityTier::Tier0);
        assert!(!matched, "disorder requires Tier2 and must not match a Tier0 context");

        let (matched, _) = run(&IpFragStrategy, CapabilityTier::Tier2);
        assert!(!matched, "ip_frag requires Tier3 and must not match a Tier2 context");
    }

    #[test]
    fn fake_oob_and_fake_rst_plan_their_actions() {
        let (_, plan) = run(&FakeStrategy, CapabilityTier::Tier0);
        assert_eq!(plan.actions, [DesyncAction::WriteFake { ttl: None, sni_mode: None, payload_file: None }]);

        let (_, plan) = run(&OobStrategy, CapabilityTier::Tier1);
        assert_eq!(plan.actions, [DesyncAction::WriteUrgent { prefix: Vec::new(), urgent_byte: 0 }]);

        let (_, plan) = run(&FakeRstStrategy, CapabilityTier::Tier2);
        assert_eq!(plan.actions, [DesyncAction::SendFakeRst { ttl: None }]);
    }
}
