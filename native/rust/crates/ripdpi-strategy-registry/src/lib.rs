//! Strategy registry and chain executor for desync backends.

use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_strategy_trait::{DesyncPlan, DesyncStrategy, StrategyContext, StrategyDescriptor, StrategyVerdict};

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
