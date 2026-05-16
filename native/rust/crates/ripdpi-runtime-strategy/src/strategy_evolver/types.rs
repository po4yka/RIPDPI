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
pub(crate) use identity::{entropy_mode_disc, offset_base_disc, quic_fake_disc, tls_randrec_disc, udp_burst_disc};
pub(crate) use pool::{combo_from_pool, COMBO_POOL};
pub(crate) use stats::{combo_fitness_at, combo_fitness_at_with_penalties, CooldownTransition, FITNESS_LATENCY_CAP_MS};
#[cfg(test)]
pub(crate) use stats::{
    rarity_penalty, retry_cost, LOSS_HALF_LIFE_MS, RARITY_FLOOR, RARITY_PENALTY, RETRY_COST_FACTOR, RETRY_SATURATION,
    WIN_HALF_LIFE_MS,
};
pub(crate) use time::now_millis;
