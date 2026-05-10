//! Strategy registry and chain executor for desync backends.

use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, RuntimeCapability, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyVerdict,
};
use thiserror::Error;

/// Error policy applied when a strategy fails to build a plan.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum OnFail {
    /// Try the next registered matching strategy.
    #[default]
    Next,
    /// Stop and proceed without desync actions.
    FallbackPlain,
    /// Stop and drop the packet or connection.
    Drop,
}

/// Error returned when resolving a named strategy fails.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum StrategyRegistryError {
    /// No built-in strategy exists for the requested ID.
    #[error("unknown strategy type: {0}")]
    UnknownType(String),
}

struct RegistryEntry {
    strategy: Box<dyn DesyncStrategy>,
    descriptor: StrategyDescriptor,
    on_fail: OnFail,
}

/// Ordered registry of strategy backends.
#[derive(Default)]
pub struct StrategyRegistry {
    entries: Vec<RegistryEntry>,
}

impl StrategyRegistry {
    /// Creates an empty registry.
    pub fn new() -> Self {
        Self::default()
    }

    /// Creates a registry preloaded with built-in desync technique descriptors.
    pub fn with_builtin_techniques() -> Self {
        let mut registry = Self::new();
        registry.register_builtin_techniques();
        registry
    }

    /// Registers the built-in RIPDPI desync techniques.
    pub fn register_builtin_techniques(&mut self) {
        for technique in BUILTIN_TECHNIQUES {
            self.register_builtin_technique(technique.id).expect("built-in technique IDs must resolve");
        }
    }

    /// Registers one built-in RIPDPI desync technique by stable ID.
    pub fn register_builtin_technique(&mut self, id: &str) -> Result<(), StrategyRegistryError> {
        let definition = builtin_technique(id)?;
        if let Some(strategy) = ripdpi_strategy_http::strategy_by_id(id) {
            self.register(strategy);
        } else {
            self.register(Box::new(BuiltinTechnique { definition }));
        }
        Ok(())
    }

    /// Registers a strategy with the default `NEXT` failure policy.
    pub fn register(&mut self, strategy: Box<dyn DesyncStrategy>) {
        self.register_with_policy(strategy, OnFail::Next);
    }

    /// Registers a strategy with an explicit failure policy.
    pub fn register_with_policy(&mut self, strategy: Box<dyn DesyncStrategy>, on_fail: OnFail) {
        let descriptor = strategy.describe();
        self.entries.push(RegistryEntry { strategy, descriptor, on_fail });
    }

    /// Executes the first successful matching strategy in registry order.
    pub fn execute(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> StrategyVerdict {
        for entry in &self.entries {
            if !entry.strategy.matches(ctx) {
                continue;
            }

            let checkpoint = plan.clone();
            match entry.strategy.plan(ctx, plan) {
                Ok(()) => return apply_plan_verdict(plan),
                Err(_error) => match entry.on_fail {
                    OnFail::Next => {
                        *plan = checkpoint;
                    }
                    OnFail::FallbackPlain => {
                        plan.actions.clear();
                        plan.verdict = StrategyVerdict::FallbackPlain;
                        return StrategyVerdict::FallbackPlain;
                    }
                    OnFail::Drop => {
                        plan.verdict = StrategyVerdict::Drop;
                        return StrategyVerdict::Drop;
                    }
                },
            }
        }

        StrategyVerdict::FallbackPlain
    }

    /// Returns descriptors for diagnostics and UI surfaces.
    pub fn list(&self) -> impl Iterator<Item = &StrategyDescriptor> {
        self.entries.iter().map(|entry| &entry.descriptor)
    }

    /// Returns a registered strategy descriptor by stable ID.
    pub fn get(&self, id: &str) -> Option<&StrategyDescriptor> {
        self.entries.iter().find(|entry| entry.descriptor.id == id).map(|entry| &entry.descriptor)
    }

    /// Translates adaptive UCB1 hints into a concrete registered strategy order.
    pub fn suggest_strategy_chain(&self, hints: &AdaptivePlannerHints) -> Vec<&str> {
        let mut scored = self
            .entries
            .iter()
            .enumerate()
            .map(|(index, entry)| {
                (entry.descriptor.id.as_str(), hint_score(entry.descriptor.id.as_str(), *hints), index)
            })
            .collect::<Vec<_>>();

        scored.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.2.cmp(&right.2)));
        scored.into_iter().map(|(id, _score, _index)| id).collect()
    }
}

#[derive(Clone, Copy, Debug)]
struct BuiltinTechnique {
    definition: &'static BuiltinTechniqueDefinition,
}

impl DesyncStrategy for BuiltinTechnique {
    fn id(&self) -> &str {
        self.definition.id
    }

    fn matches(&self, ctx: &StrategyContext<'_>) -> bool {
        tier_rank(ctx.caps.tier) >= tier_rank(self.definition.required_tier)
    }

    fn plan(&self, _ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        match self.definition.id {
            "split" => plan.actions.push(DesyncAction::Split { offset: 0, disorder: false }),
            "disorder" => plan.actions.push(DesyncAction::Split { offset: 0, disorder: true }),
            "fake" | "oob" | "fake_rst" | "ip_frag" | "multi_disorder" => {
                plan.actions.push(DesyncAction::RawSend(Vec::new()));
            }
            "seq_overlap" | "tls_rec" | "tls_rand_rec" => plan.actions.push(DesyncAction::Write(Vec::new())),
            "udplen" | "http_domcase" | "http_hostcase" | "wsize" | "wssize" => {}
            _ => {
                return Err(StrategyError::InvalidConfig(format!("unknown built-in technique {}", self.definition.id)))
            }
        }
        Ok(())
    }

    fn describe(&self) -> StrategyDescriptor {
        StrategyDescriptor {
            id: self.definition.id.to_owned(),
            label: self.definition.label.to_owned(),
            required_tier: self.definition.required_tier,
            required_capabilities: self.definition.required_capabilities.to_vec(),
            ..StrategyDescriptor::default()
        }
    }
}

#[derive(Debug)]
struct BuiltinTechniqueDefinition {
    id: &'static str,
    label: &'static str,
    required_tier: CapabilityTier,
    required_capabilities: &'static [RuntimeCapability],
}

const TCP_REPAIR_CAPS: &[RuntimeCapability] = &[RuntimeCapability::ReplacementSocket];
const VPN_CAPS: &[RuntimeCapability] = &[RuntimeCapability::VpnMode];
const WINDOW_CLAMP_CAPS: &[RuntimeCapability] = &[RuntimeCapability::TcpWindowClamp];

const BUILTIN_TECHNIQUES: &[BuiltinTechniqueDefinition] = &[
    BuiltinTechniqueDefinition {
        id: "split",
        label: "Split",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "disorder",
        label: "Disorder",
        required_tier: CapabilityTier::Tier2,
        required_capabilities: TCP_REPAIR_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "fake",
        label: "Fake packet",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "oob",
        label: "TCP urgent/OOB",
        required_tier: CapabilityTier::Tier1,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "fake_rst",
        label: "Fake RST",
        required_tier: CapabilityTier::Tier2,
        required_capabilities: TCP_REPAIR_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "seq_overlap",
        label: "Sequence overlap",
        required_tier: CapabilityTier::Tier2,
        required_capabilities: TCP_REPAIR_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "ip_frag",
        label: "IP fragmentation",
        required_tier: CapabilityTier::Tier3,
        required_capabilities: VPN_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "multi_disorder",
        label: "Multi-disorder",
        required_tier: CapabilityTier::Tier2,
        required_capabilities: TCP_REPAIR_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "tls_rec",
        label: "TLS record split",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "tls_rand_rec",
        label: "TLS random records",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "udplen",
        label: "UDP length falsification",
        required_tier: CapabilityTier::Tier3,
        required_capabilities: VPN_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "http_domcase",
        label: "HTTP domain case",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "http_hostcase",
        label: "HTTP host case",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "http_methodeol",
        label: "HTTP method EOL",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "http_unixeol",
        label: "HTTP Unix EOL",
        required_tier: CapabilityTier::Tier0,
        required_capabilities: &[],
    },
    BuiltinTechniqueDefinition {
        id: "wsize",
        label: "TCP window size",
        required_tier: CapabilityTier::Tier1,
        required_capabilities: WINDOW_CLAMP_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "wssize",
        label: "TCP window scale size",
        required_tier: CapabilityTier::Tier1,
        required_capabilities: WINDOW_CLAMP_CAPS,
    },
];

fn tier_rank(tier: CapabilityTier) -> u8 {
    match tier {
        CapabilityTier::Tier0 => 0,
        CapabilityTier::Tier1 => 1,
        CapabilityTier::Tier2 => 2,
        CapabilityTier::Tier3 => 3,
    }
}

fn apply_plan_verdict(plan: &mut DesyncPlan) -> StrategyVerdict {
    match plan.verdict {
        StrategyVerdict::Apply => StrategyVerdict::Apply,
        StrategyVerdict::FallbackPlain => {
            plan.actions.clear();
            StrategyVerdict::FallbackPlain
        }
        StrategyVerdict::Drop => StrategyVerdict::Drop,
    }
}

fn hint_score(id: &str, hints: AdaptivePlannerHints) -> u8 {
    let mut score = 0_u8;

    if hints.tls_record_offset_base.is_some() || hints.tlsrandrec_profile.is_some() {
        score = score.max(score_if(id, &["tlsrec", "tlsrandrec"], 100));
    }
    if hints.split_offset_base.is_some() {
        score = score.max(score_if(id, &["split"], 90));
    }
    if hints.quic_fake_profile.is_some() {
        score = score.max(score_if(id, &["quic"], 85));
    }
    if hints.udp_burst_profile.is_some() {
        score = score.max(score_if(id, &["udp", "quic", "burst"], 80));
    }
    if hints.entropy_mode.is_some() {
        score = score.max(score_if(id, &["fake", "hostfake", "tlsrand"], 70));
    }

    score
}

fn score_if(id: &str, needles: &[&str], score: u8) -> u8 {
    if needles.iter().any(|needle| id.contains(needle)) {
        score
    } else {
        0
    }
}

fn builtin_technique(id: &str) -> Result<&'static BuiltinTechniqueDefinition, StrategyRegistryError> {
    BUILTIN_TECHNIQUES
        .iter()
        .find(|definition| definition.id == id)
        .ok_or_else(|| StrategyRegistryError::UnknownType(id.to_owned()))
}
