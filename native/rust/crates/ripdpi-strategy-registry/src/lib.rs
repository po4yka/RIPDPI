//! Strategy registry and chain executor for desync backends.
//!
//! [`StrategyRegistry`] aggregates every `ripdpi-strategy-*` implementation
//! into an ordered chain and runs it: the first [`matches`](DesyncStrategy::matches)ing
//! strategy whose [`plan`](DesyncStrategy::plan) succeeds wins, with a
//! per-strategy [`OnFail`] policy on failure.
//!
//! Strategy resolution is a **descriptor/factory platform**. Every strategy
//! step is one [`StrategyStepRegistration`] in the
//! [`STRATEGY_STEP_REGISTRATIONS`] `linkme` slice — a [`StrategyStepDescriptor`]
//! paired with the [`StrategyStepFactory`] that builds it. The registry
//! resolves a step by descriptor id and dispatches through the factory:
//! `Stateless` for a zero-argument default, `Configured` for a step built from
//! parsed parameters, `Unimplemented` for a known-but-not-yet-built kind. There
//! is no `BUILTIN_TECHNIQUES` table and no central `match` over step ids —
//! adding a strategy is one registration in its `ripdpi-strategy-*` crate.
//!
//! Lua is the one special case: `ripdpi-strategy-lua` is feature-gated, so the
//! `lua` step is resolved directly by the registry — not through the slice —
//! and [`LUA_STEP_DESCRIPTOR`] is registry-owned.
//!
//! See `README.md` and `FEATURE_EXTENSION_GUIDE.md` §1 for how to add a
//! strategy and the one central edit (the `extern crate` force-link) the
//! `linkme` seam still needs.

#![forbid(unsafe_code)]

extern crate ripdpi_strategy_core as _;
extern crate ripdpi_strategy_http as _;
extern crate ripdpi_strategy_ipv6 as _;
extern crate ripdpi_strategy_udp as _;
extern crate ripdpi_strategy_window as _;

pub mod fixed_config;

pub use fixed_config::{CandidateArm, CandidateArmViolation, FIXED_CONFIG_PROTOCOL_AMNEZIAWG, FixedConfigProtocols};

use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_strategy_config::{LoadedStrategyConfig, OnFail as ConfigOnFail, StrategyStep};
use ripdpi_strategy_trait::{
    CapabilityTier, DesyncPlan, DesyncStrategy, STRATEGY_DESCRIPTOR_REGISTRATIONS, STRATEGY_STEP_REGISTRATIONS,
    StrategyContext, StrategyDescriptor, StrategyError, StrategyStepDescriptor, StrategyStepFactory,
    StrategyStepParams, StrategyStepRegistration, StrategyVerdict,
};
use thiserror::Error;

/// The `lua` strategy step descriptor.
///
/// Lua is feature-gated (`lua-strategies`) and special-cased in
/// [`build_step_strategy`], so — unlike every other step kind — it is not
/// contributed to [`STRATEGY_STEP_REGISTRATIONS`]; the registry owns its
/// descriptor here. [`StrategyRegistry::step_descriptors`] folds it back in.
pub const LUA_STEP_DESCRIPTOR: StrategyStepDescriptor = StrategyStepDescriptor {
    id: "lua",
    label: "Lua strategy",
    aliases: &[],
    required_tier: CapabilityTier::Tier0,
    required_capabilities: &[],
    needs_parameters: true,
    parameter_schema: "function: Lua function name, script_paths: [Lua script path]",
};

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
    /// No registered strategy exists for the requested ID.
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

    /// Creates a registry preloaded with the built-in desync techniques.
    pub fn with_builtin_techniques() -> Self {
        let mut registry = Self::new();
        registry.register_builtin_techniques();
        registry
    }

    /// Registers every link-time built-in strategy step, in stable id order.
    pub fn register_builtin_techniques(&mut self) {
        let mut ids: Vec<&'static str> =
            STRATEGY_STEP_REGISTRATIONS.iter().map(|registration| registration.descriptor.id).collect();
        ids.sort_unstable();
        for id in ids {
            self.register_builtin_technique(id).expect("built-in technique IDs must resolve");
        }
    }

    /// Registers one built-in strategy step by stable ID.
    pub fn register_builtin_technique(&mut self, id: &str) -> Result<(), StrategyRegistryError> {
        self.register_builtin_technique_with_policy(id, OnFail::Next)
    }

    /// Registers one built-in strategy step by stable ID and failure policy.
    ///
    /// A `Configured` step is built with [`StrategyStepParams::default`]; an
    /// `Unimplemented` step registers a placeholder whose `plan` fails. `lua`
    /// is not a bare built-in technique — it needs config — and resolves to
    /// [`StrategyRegistryError::UnknownType`], as it always has.
    pub fn register_builtin_technique_with_policy(
        &mut self,
        id: &str,
        on_fail: OnFail,
    ) -> Result<(), StrategyRegistryError> {
        let registration = step_registration(id).ok_or_else(|| StrategyRegistryError::UnknownType(id.to_owned()))?;
        let strategy = build_registered_strategy(registration, &StrategyStepParams::default());
        self.register_with_policy(strategy, on_fail);
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
                let resolved = build_step_strategy(step)?;
                self.register_with_policy(resolved, on_fail);
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

    /// Returns strategy IDs contributed through link-time step registration.
    pub fn linked_strategy_ids() -> impl Iterator<Item = &'static str> {
        STRATEGY_STEP_REGISTRATIONS.iter().map(|registration| registration.descriptor.id)
    }

    /// Returns descriptor-only planner IDs contributed through link-time
    /// registration (the `tcp_desync` chain planner).
    pub fn linked_descriptor_ids() -> impl Iterator<Item = &'static str> {
        STRATEGY_DESCRIPTOR_REGISTRATIONS.iter().map(|registration| registration.id)
    }

    /// Returns descriptor-only planner metadata contributed through link-time
    /// registration.
    pub fn linked_descriptors() -> impl Iterator<Item = StrategyDescriptor> {
        STRATEGY_DESCRIPTOR_REGISTRATIONS.iter().map(|registration| (registration.describe)())
    }

    /// Returns every strategy step descriptor the registry can resolve — the
    /// link-time [`STRATEGY_STEP_REGISTRATIONS`] descriptors plus the
    /// registry-owned [`LUA_STEP_DESCRIPTOR`].
    pub fn step_descriptors() -> impl Iterator<Item = StrategyStepDescriptor> {
        STRATEGY_STEP_REGISTRATIONS
            .iter()
            .map(|registration| registration.descriptor)
            .chain(std::iter::once(LUA_STEP_DESCRIPTOR))
    }

    /// Looks up a strategy step descriptor by stable ID.
    pub fn step_descriptor(id: &str) -> Option<StrategyStepDescriptor> {
        Self::step_descriptors().find(|descriptor| descriptor.id == id)
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

/// Placeholder for a [`StrategyStepFactory::Unimplemented`] step kind — a
/// descriptor-only technique (`synack`, `synack_split`) with no `DesyncStrategy`
/// implementation yet.
///
/// `plan` always fails, so an `OnFail::Next` chain skips past it exactly as the
/// former `BuiltinTechnique` adapter did; `describe` still surfaces the
/// descriptor's tier and capabilities for inventory and UI.
#[derive(Clone, Copy, Debug)]
struct UnimplementedStrategy {
    descriptor: StrategyStepDescriptor,
}

impl UnimplementedStrategy {
    fn new(descriptor: StrategyStepDescriptor) -> Self {
        Self { descriptor }
    }
}

impl DesyncStrategy for UnimplementedStrategy {
    fn id(&self) -> &str {
        self.descriptor.id
    }

    fn matches(&self, ctx: &StrategyContext<'_>) -> bool {
        ctx.caps.tier >= self.descriptor.required_tier
    }

    fn plan(&self, _ctx: &StrategyContext<'_>, _plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        Err(StrategyError::InvalidConfig(format!(
            "strategy {} is registered but not yet implemented",
            self.descriptor.id,
        )))
    }

    fn describe(&self) -> StrategyDescriptor {
        StrategyDescriptor {
            id: self.descriptor.id.to_owned(),
            label: self.descriptor.label.to_owned(),
            required_tier: self.descriptor.required_tier,
            required_capabilities: self.descriptor.required_capabilities.to_vec(),
            ..StrategyDescriptor::default()
        }
    }
}

/// Looks up a step registration by stable ID.
fn step_registration(id: &str) -> Option<&'static StrategyStepRegistration> {
    STRATEGY_STEP_REGISTRATIONS.iter().find(|registration| registration.descriptor.id == id)
}

/// Builds the strategy a registration describes, dispatching through its
/// [`StrategyStepFactory`].
fn build_registered_strategy(
    registration: &StrategyStepRegistration,
    params: &StrategyStepParams,
) -> Box<dyn DesyncStrategy> {
    match registration.factory {
        StrategyStepFactory::Stateless(make) => make(),
        StrategyStepFactory::Configured(build) => build(params),
        StrategyStepFactory::Unimplemented => Box::new(UnimplementedStrategy::new(registration.descriptor)),
    }
}

/// Materializes the strategy a parsed config step resolves to.
fn build_step_strategy(step: &StrategyStep) -> Result<Box<dyn DesyncStrategy>, StrategyRegistryError> {
    let id = step.kind.registry_id();
    if id == "lua" {
        return configured_lua_strategy_from_step(step);
    }
    let registration = step_registration(id).ok_or_else(|| StrategyRegistryError::UnknownType(id.to_owned()))?;
    Ok(build_registered_strategy(registration, &params_from_step(step)))
}

/// Projects a parsed config step onto the schema-neutral parameter bag the
/// configured-strategy factories consume.
fn params_from_step(step: &StrategyStep) -> StrategyStepParams {
    StrategyStepParams {
        pos: step.pos.clone(),
        disorder: step.disorder,
        ttl: step.ttl,
        sni_mode: step.sni_mode.clone(),
        delta: step.delta,
        value: step.value,
        size: step.size,
        scale: step.scale,
        ext_type: step.ext_type.clone(),
        function: step.function.clone(),
        script_paths: step.script_paths.clone(),
        forward_original: step.forward_original,
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
    if needles.iter().any(|needle| id.contains(needle)) { score } else { 0 }
}

fn on_fail_from_config(on_fail: ConfigOnFail) -> OnFail {
    match on_fail {
        ConfigOnFail::NextStrategy => OnFail::Next,
        ConfigOnFail::FallbackPlain => OnFail::FallbackPlain,
        ConfigOnFail::Drop => OnFail::Drop,
    }
}

#[cfg(feature = "lua-strategies")]
fn configured_lua_strategy_from_step(step: &StrategyStep) -> Result<Box<dyn DesyncStrategy>, StrategyRegistryError> {
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
        .map_err(|error| StrategyRegistryError::Lua { function: function.to_owned(), error: error.to_string() })
}

#[cfg(not(feature = "lua-strategies"))]
fn configured_lua_strategy_from_step(step: &StrategyStep) -> Result<Box<dyn DesyncStrategy>, StrategyRegistryError> {
    let function = step.function.as_deref().unwrap_or("<missing>");
    Err(StrategyRegistryError::Lua {
        function: function.to_owned(),
        error: "lua-strategies feature is disabled".to_owned(),
    })
}
