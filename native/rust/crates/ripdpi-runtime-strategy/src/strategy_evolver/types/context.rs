use std::collections::{HashMap, HashSet};

use ripdpi_config::EnvironmentKind;

use super::identity::StrategyCombo;
use super::stats::ComboStats;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningTargetBucket {
    #[default]
    Generic,
    Tls,
    Ech,
    Quic,
    Control,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningTransportKind {
    #[default]
    Unknown,
    Tcp,
    UdpQuic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningAlpnClass {
    #[default]
    Unknown,
    Http1,
    H2Http11,
    H3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningHostingFamily {
    #[default]
    Unknown,
    Direct,
    Cloudflare,
    Google,
    DomesticCdn,
    ForeignCdn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LearningReachabilitySet {
    #[default]
    Unknown,
    Control,
    Domestic,
    Foreign,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum ResolverHealthClass {
    #[default]
    Unknown,
    Healthy,
    Degraded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum CapabilityContext {
    #[default]
    Unknown,
    Full,
    Degraded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(crate) enum StrategyFamily {
    Baseline,
    SplitOffset,
    TlsRecordOffset,
    TlsRandRec,
    UdpBurst,
    QuicFake,
    FakeTtl,
    Entropy,
    TimingJitter,
    OobPlacement,
    Mixed,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub struct LearningContext {
    pub network_identity: Option<String>,
    pub target_bucket: LearningTargetBucket,
    pub transport: LearningTransportKind,
    pub alpn_class: LearningAlpnClass,
    pub hosting_family: LearningHostingFamily,
    pub reachability_set: LearningReachabilitySet,
    pub ech_capable: bool,
    pub resolver_health: ResolverHealthClass,
    pub rooted: bool,
    pub capability_context: CapabilityContext,
    /// Coarse classification of the host device — `Field` for real user
    /// devices, `Emulator` for AVD / CI test devices, `Unknown` when the
    /// platform-side detector has not provided a value.
    /// Including this here automatically segregates field-derived bandit
    /// statistics from emulator-derived ones via the `HashMap`'s
    /// per-context state.
    pub environment: EnvironmentKind,
}

#[derive(Debug, Clone, Default)]
pub(crate) struct FamilyStats {
    pub(crate) attempts: u32,
    pub(crate) total_reward: f64,
}

#[derive(Debug, Clone, Default)]
pub(crate) struct ContextBanditState {
    pub(crate) combos: HashMap<StrategyCombo, ComboStats>,
    pub(crate) families: HashMap<StrategyFamily, FamilyStats>,
    pub(crate) piloted_buckets: HashSet<LearningTargetBucket>,
    pub(crate) niche_winners: HashMap<LearningTargetBucket, StrategyCombo>,
}
