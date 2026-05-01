use super::{ActivationFilter, OffsetExpr};

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
    pub kind: TcpChainStepKind,
    pub offset: OffsetExpr,
    pub activation_filter: Option<ActivationFilter>,
    pub midhost_offset: Option<OffsetExpr>,
    pub fake_host_template: Option<String>,
    pub fake_order: FakeOrder,
    pub fake_seq_mode: FakeSeqMode,
    pub tcp_flags_set: Option<u16>,
    pub tcp_flags_unset: Option<u16>,
    pub tcp_flags_orig_set: Option<u16>,
    pub tcp_flags_orig_unset: Option<u16>,
    pub overlap_size: i32,
    pub seqovl_fake_mode: SeqOverlapFakeMode,
    pub fragment_count: i32,
    pub min_fragment_size: i32,
    pub max_fragment_size: i32,
    pub inter_segment_delay_ms: u32,
    /// Send IP fragments in reverse order (second before first) to evade
    /// DPI systems that expect sequential fragment delivery.
    pub ip_frag_disorder: bool,
    /// Insert IPv6 Hop-by-Hop Options extension header (no-op for IPv4).
    pub ipv6_hop_by_hop: bool,
    /// Insert IPv6 Destination Options header in unfragmentable part.
    pub ipv6_dest_opt: bool,
    /// Insert IPv6 Destination Options header in fragmentable part.
    pub ipv6_dest_opt2: bool,
    /// Insert IPv6 Routing extension header (type 0, segments_left=0).
    pub ipv6_routing: bool,
    /// Override second fragment's next_header (IPv6 only, RFC 8200 forgery).
    pub ipv6_frag_next_override: Option<u8>,
    /// When true, seed fake hostname generation from OS entropy instead of the
    /// deterministic connection seed, producing a different domain per connection
    /// that cannot be predicted or cached by DPI.
    pub random_fake_host: bool,
}

impl TcpChainStep {
    pub const fn new(kind: TcpChainStepKind, offset: OffsetExpr) -> Self {
        Self {
            kind,
            offset,
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fake_order: FakeOrder::BeforeEach,
            fake_seq_mode: FakeSeqMode::Duplicate,
            tcp_flags_set: None,
            tcp_flags_unset: None,
            tcp_flags_orig_set: None,
            tcp_flags_orig_unset: None,
            overlap_size: 0,
            seqovl_fake_mode: SeqOverlapFakeMode::Profile,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_routing: false,
            ipv6_frag_next_override: None,
            random_fake_host: false,
        }
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
