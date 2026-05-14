//! Strategy registry and chain executor for desync backends.

extern crate ripdpi_strategy_http as _;
extern crate ripdpi_strategy_ipv6 as _;
extern crate ripdpi_strategy_udp as _;
extern crate ripdpi_strategy_window as _;

pub mod fixed_config;

pub use fixed_config::{CandidateArm, CandidateArmViolation, FixedConfigProtocols, FIXED_CONFIG_PROTOCOL_AMNEZIAWG};

use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_strategy_config::{LoadedStrategyConfig, OnFail as ConfigOnFail, StepType, StrategyStep};
use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, RuntimeCapability, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyFactory, StrategyVerdict, STRATEGY_DESCRIPTOR_REGISTRATIONS, STRATEGY_FACTORIES,
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
    /// Lua strategy configuration was invalid or failed to load.
    #[error("Lua strategy {function} failed: {error}")]
    Lua { function: String, error: String },
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
        self.register_builtin_technique_with_policy(id, OnFail::Next)
    }

    /// Registers one built-in RIPDPI desync technique by stable ID and failure policy.
    pub fn register_builtin_technique_with_policy(
        &mut self,
        id: &str,
        on_fail: OnFail,
    ) -> Result<(), StrategyRegistryError> {
        if let Some(factory) = linked_strategy_factory(id) {
            self.register_with_policy((factory.make)(), on_fail);
        } else {
            let definition = builtin_technique(id)?;
            self.register_with_policy(Box::new(BuiltinTechnique { definition }), on_fail);
        }
        Ok(())
    }

    /// Builds a registry from a parsed YAML/TOML strategy config.
    pub fn from_loaded_config(config: &LoadedStrategyConfig) -> Result<Self, StrategyRegistryError> {
        let mut registry = Self::new();
        registry.register_loaded_config(config)?;
        Ok(registry)
    }

    /// Registers all strategy steps from a parsed YAML/TOML strategy config.
    pub fn register_loaded_config(&mut self, config: &LoadedStrategyConfig) -> Result<(), StrategyRegistryError> {
        for strategy in &config.strategies {
            let on_fail = on_fail_from_config(strategy.on_fail);
            for step in &strategy.steps {
                if let Some(strategy) = configured_strategy_from_step(step)? {
                    self.register_with_policy(strategy, on_fail);
                } else {
                    self.register_builtin_technique_with_policy(step.kind.registry_id(), on_fail)?;
                }
            }
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

    /// Returns strategy IDs contributed through link-time factory registration.
    pub fn linked_strategy_ids() -> impl Iterator<Item = &'static str> {
        STRATEGY_FACTORIES.iter().map(|factory| factory.id)
    }

    /// Returns descriptor-only IDs contributed through link-time registration.
    pub fn linked_descriptor_ids() -> impl Iterator<Item = &'static str> {
        STRATEGY_DESCRIPTOR_REGISTRATIONS.iter().map(|registration| registration.id)
    }

    /// Returns descriptors contributed through link-time registration.
    pub fn linked_descriptors() -> impl Iterator<Item = StrategyDescriptor> {
        STRATEGY_DESCRIPTOR_REGISTRATIONS.iter().map(|registration| (registration.describe)())
    }

    /// Validates a candidate arm against the default fixed-config protocol hints.
    ///
    /// Rejects arms that would mutate a fixed-config protocol's params (e.g. an
    /// AmneziaWG profile's obfuscation params), which would break its handshake.
    pub fn validate_candidate_arm(&self, arm: &CandidateArm) -> Result<(), CandidateArmViolation> {
        FixedConfigProtocols::default().validate_candidate_arm(arm)
    }

    /// Filters candidate arms through the default fixed-config protocol hints,
    /// dropping any arm that mutates a fixed-config protocol's params.
    pub fn filter_candidate_arms(&self, arms: &[CandidateArm]) -> Vec<CandidateArm> {
        FixedConfigProtocols::default().filter_candidate_arms(arms)
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
            "fake" => plan.actions.push(DesyncAction::WriteFake { ttl: None, sni_mode: None, payload_file: None }),
            "oob" => plan.actions.push(DesyncAction::WriteUrgent { prefix: Vec::new(), urgent_byte: 0 }),
            "fake_rst" => plan.actions.push(DesyncAction::SendFakeRst { ttl: None }),
            "udplen" => plan.actions.push(DesyncAction::UdpLen { delta: 0 }),
            "ip_frag" | "multi_disorder" => plan.actions.push(DesyncAction::RawSend(Vec::new())),
            "seq_overlap" | "tls_rec" | "tls_rand_rec" => plan.actions.push(DesyncAction::Write(Vec::new())),
            "ipv6_ext" | "http_domcase" | "http_hostcase" | "wsize" | "wssize" => {}
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
const SYNACK_CAPS: &[RuntimeCapability] =
    &[RuntimeCapability::VpnMode, RuntimeCapability::RawTcpFakeSend, RuntimeCapability::VpnProtect];

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
        id: "ipv6_ext",
        label: "IPv6 extension header injection",
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
    BuiltinTechniqueDefinition {
        id: "synack",
        label: "SYN-ACK low TTL",
        required_tier: CapabilityTier::Tier3,
        required_capabilities: SYNACK_CAPS,
    },
    BuiltinTechniqueDefinition {
        id: "synack_split",
        label: "SYN-ACK split",
        required_tier: CapabilityTier::Tier3,
        required_capabilities: SYNACK_CAPS,
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

fn on_fail_from_config(on_fail: ConfigOnFail) -> OnFail {
    match on_fail {
        ConfigOnFail::NextStrategy => OnFail::Next,
        ConfigOnFail::FallbackPlain => OnFail::FallbackPlain,
        ConfigOnFail::Drop => OnFail::Drop,
    }
}

fn builtin_technique(id: &str) -> Result<&'static BuiltinTechniqueDefinition, StrategyRegistryError> {
    BUILTIN_TECHNIQUES
        .iter()
        .find(|definition| definition.id == id)
        .ok_or_else(|| StrategyRegistryError::UnknownType(id.to_owned()))
}

fn configured_strategy_from_step(
    step: &StrategyStep,
) -> Result<Option<Box<dyn DesyncStrategy>>, StrategyRegistryError> {
    match step.kind {
        StepType::Udplen => {
            let delta = step.delta.unwrap_or(4).clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16;
            Ok(Some(Box::new(ripdpi_strategy_udp::UdpLenStrategy::new(delta))))
        }
        StepType::Ipv6Ext => {
            let ext_type =
                step.ext_type.as_deref().and_then(ripdpi_strategy_ipv6::Ipv6ExtType::parse).unwrap_or_default();
            Ok(Some(Box::new(ripdpi_strategy_ipv6::Ipv6ExtHdrStrategy::new(ext_type))))
        }
        StepType::Lua => configured_lua_strategy_from_step(step),
        _ => Ok(None),
    }
}

#[cfg(feature = "lua-strategies")]
fn configured_lua_strategy_from_step(
    step: &StrategyStep,
) -> Result<Option<Box<dyn DesyncStrategy>>, StrategyRegistryError> {
    let function = step.function.as_deref().map(str::trim).filter(|value| !value.is_empty()).ok_or_else(|| {
        StrategyRegistryError::Lua { function: "<missing>".to_owned(), error: "missing Lua function name".to_owned() }
    })?;
    if step.script_paths.is_empty() {
        return Err(StrategyRegistryError::Lua {
            function: function.to_owned(),
            error: "missing Lua script_paths".to_owned(),
        });
    }

    let engine = ripdpi_strategy_lua::LuaStrategyEngine::new()
        .map_err(|error| StrategyRegistryError::Lua { function: function.to_owned(), error: error.to_string() })?;
    for path in &step.script_paths {
        let path = path.trim();
        if path.is_empty() {
            return Err(StrategyRegistryError::Lua {
                function: function.to_owned(),
                error: "blank Lua script path".to_owned(),
            });
        }
        engine
            .load_script_registering_globals(path)
            .map_err(|error| StrategyRegistryError::Lua { function: function.to_owned(), error: error.to_string() })?;
    }
    engine
        .make_strategy(function)
        .map(Some)
        .map_err(|error| StrategyRegistryError::Lua { function: function.to_owned(), error: error.to_string() })
}

#[cfg(not(feature = "lua-strategies"))]
fn configured_lua_strategy_from_step(
    step: &StrategyStep,
) -> Result<Option<Box<dyn DesyncStrategy>>, StrategyRegistryError> {
    let function = step.function.as_deref().unwrap_or("<missing>");
    Err(StrategyRegistryError::Lua {
        function: function.to_owned(),
        error: "lua-strategies feature is disabled".to_owned(),
    })
}

fn linked_strategy_factory(id: &str) -> Option<&'static StrategyFactory> {
    STRATEGY_FACTORIES.iter().find(|factory| factory.id == id)
}
