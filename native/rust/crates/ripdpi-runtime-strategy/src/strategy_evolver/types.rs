mod context;
mod identity;
mod pool;
mod stats;
mod time;

pub use context::{
    CapabilityContext, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass,
};
pub use identity::StrategyCombo;
pub use stats::ComboStats;

#[cfg(test)]
pub(crate) use context::FamilyStats;
pub(crate) use context::{ContextBanditState, StrategyFamily};
#[cfg(test)]
pub(crate) use identity::UNKNOWN_VARIANT_DISC;
pub(crate) use identity::{
    entropy_mode_disc, offset_base_disc, oob_placement_disc, quic_fake_disc, timing_jitter_disc, tls_randrec_disc,
    udp_burst_disc,
};
pub(crate) use pool::{COMBO_POOL, combo_from_pool};
pub(crate) use stats::{CooldownTransition, FITNESS_LATENCY_CAP_MS, combo_fitness_at, combo_fitness_at_with_penalties};
#[cfg(test)]
pub(crate) use stats::{
    LOSS_HALF_LIFE_MS, RARITY_FLOOR, RARITY_PENALTY, RETRY_COST_FACTOR, RETRY_SATURATION, WIN_HALF_LIFE_MS,
    rarity_penalty, retry_cost,
};
pub(crate) use time::now_millis;
