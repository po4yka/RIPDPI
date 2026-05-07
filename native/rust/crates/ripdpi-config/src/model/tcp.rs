mod payload;

use super::{ActivationFilter, OffsetExpr};
use payload::TcpStepPayloadStorage;

pub use payload::*;

#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesyncMode {
    None = 0,
    Split = 1,
    Disorder = 2,
    Oob = 3,
    Disoob = 4,
    Fake = 5,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartSpec {
    pub mode: DesyncMode,
    pub offset: OffsetExpr,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TcpChainStepKind {
    Split,
    SynData,
    SeqOverlap,
    Disorder,
    MultiDisorder,
    Fake,
    FakeSplit,
    FakeDisorder,
    HostFake,
    Oob,
    Disoob,
    TlsRec,
    TlsRandRec,
    IpFrag2,
    FakeRst,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum EmitterTier {
    NonRootProduction,
    RootedProduction,
    LabDiagnosticsOnly,
}

impl EmitterTier {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::NonRootProduction => "non_root_production",
            Self::RootedProduction => "rooted_production",
            Self::LabDiagnosticsOnly => "lab_diagnostics_only",
        }
    }
}

impl TcpChainStepKind {
    pub const fn from_mode(mode: DesyncMode) -> Option<Self> {
        match mode {
            DesyncMode::None | DesyncMode::Split => Some(Self::Split),
            DesyncMode::Disorder => Some(Self::Disorder),
            DesyncMode::Oob => Some(Self::Oob),
            DesyncMode::Disoob => Some(Self::Disoob),
            DesyncMode::Fake => Some(Self::Fake),
        }
    }

    pub const fn as_mode(self) -> Option<DesyncMode> {
        match self {
            Self::Split => Some(DesyncMode::Split),
            Self::SynData => Some(DesyncMode::Split),
            Self::SeqOverlap => Some(DesyncMode::Split),
            Self::Disorder => Some(DesyncMode::Disorder),
            Self::MultiDisorder => None,
            Self::Fake => Some(DesyncMode::Fake),
            Self::FakeSplit => Some(DesyncMode::Fake),
            Self::FakeDisorder => Some(DesyncMode::Disorder),
            Self::HostFake => None,
            Self::Oob => Some(DesyncMode::Oob),
            Self::Disoob => Some(DesyncMode::Disoob),
            Self::TlsRec => None,
            Self::TlsRandRec => None,
            Self::IpFrag2 => None,
            Self::FakeRst => None,
        }
    }

    pub const fn is_tls_prelude(self) -> bool {
        matches!(self, Self::TlsRec | Self::TlsRandRec)
    }

    pub const fn supports_fake_tcp_flags(self) -> bool {
        matches!(
            self,
            Self::SeqOverlap | Self::Fake | Self::FakeSplit | Self::FakeDisorder | Self::HostFake | Self::FakeRst
        )
    }

    pub const fn supports_orig_tcp_flags(self) -> bool {
        matches!(
            self,
            Self::Split
                | Self::SynData
                | Self::Disorder
                | Self::MultiDisorder
                | Self::Fake
                | Self::FakeSplit
                | Self::FakeDisorder
                | Self::HostFake
                | Self::IpFrag2
        )
    }

    pub const fn supports_fake_ordering(self) -> bool {
        matches!(self, Self::Fake | Self::FakeSplit | Self::FakeDisorder | Self::HostFake)
    }

    pub const fn emitter_tier(self) -> EmitterTier {
        match self {
            Self::SeqOverlap | Self::MultiDisorder | Self::IpFrag2 => EmitterTier::RootedProduction,
            Self::FakeRst => EmitterTier::LabDiagnosticsOnly,
            Self::Split
            | Self::SynData
            | Self::Disorder
            | Self::Fake
            | Self::FakeSplit
            | Self::FakeDisorder
            | Self::HostFake
            | Self::Oob
            | Self::Disoob
            | Self::TlsRec
            | Self::TlsRandRec => EmitterTier::NonRootProduction,
        }
    }
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum SeqOverlapFakeMode {
    #[default]
    Profile,
    Rand,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum FakePacketSource {
    #[default]
    Profile,
    CapturedClientHello,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum FakeOrder {
    #[default]
    BeforeEach,
    AllFakesFirst,
    RealFakeRealFake,
    AllRealsFirst,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum FakeSeqMode {
    #[default]
    Duplicate,
    Sequential,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpIdMode {
    Seq,
    SeqGroup,
    Rnd,
    Zero,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpChainStep {
    common: TcpStepCommon,
    payload: TcpStepPayloadStorage,
}

impl TcpChainStep {
    pub const fn new(kind: TcpChainStepKind, offset: OffsetExpr) -> Self {
        Self { common: TcpStepCommon::new(offset), payload: TcpStepPayloadStorage::default_for_kind(kind) }
    }

    pub const fn kind(&self) -> TcpChainStepKind {
        self.payload.kind()
    }

    pub const fn offset(&self) -> OffsetExpr {
        self.common.offset
    }

    pub const fn activation_filter(&self) -> Option<ActivationFilter> {
        self.common.activation_filter
    }

    pub fn with_activation_filter(mut self, activation_filter: Option<ActivationFilter>) -> Self {
        self.common.activation_filter = activation_filter;
        self
    }

    pub const fn midhost_offset(&self) -> Option<OffsetExpr> {
        self.payload.midhost_offset()
    }

    pub fn with_midhost_offset(mut self, midhost_offset: Option<OffsetExpr>) -> Self {
        self.payload.set_midhost_offset(midhost_offset);
        self
    }

    pub fn fake_host_template(&self) -> Option<&str> {
        self.payload.fake_host_template()
    }

    pub fn with_fake_host_template(mut self, fake_host_template: Option<String>) -> Self {
        self.payload.set_fake_host_template(fake_host_template);
        self
    }

    pub const fn random_fake_host(&self) -> bool {
        self.payload.random_fake_host()
    }

    pub fn with_random_fake_host(mut self, random_fake_host: bool) -> Self {
        self.payload.set_random_fake_host(random_fake_host);
        self
    }

    pub const fn inter_segment_delay_ms(&self) -> u32 {
        self.common.inter_segment_delay_ms
    }

    pub fn with_inter_segment_delay_ms(mut self, delay_ms: u32) -> Self {
        self.common.inter_segment_delay_ms = delay_ms;
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RotationCandidate {
    pub tcp_chain: Vec<TcpChainStep>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RotationPolicy {
    pub fails: usize,
    pub retrans: u32,
    pub seq: u32,
    pub rst: u32,
    pub time_secs: u64,
    /// When true, suppress desync immediately on failure detection rather than
    /// waiting for the next round boundary. The connection falls back to plain
    /// passthrough until rotation completes.
    pub cancel_on_failure: bool,
    pub candidates: Vec<RotationCandidate>,
}

impl Default for RotationPolicy {
    fn default() -> Self {
        Self {
            fails: 3,
            retrans: 3,
            seq: 65_536,
            rst: 1,
            time_secs: 60,
            cancel_on_failure: true,
            candidates: Vec::new(),
        }
    }
}
