use super::{ActivationFilter, EmitterTier};

#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpChainStepKind {
    FakeBurst,
    DummyPrepend,
    QuicSniSplit,
    QuicFakeVersion,
    QuicCryptoSplit,
    QuicPaddingLadder,
    QuicCidChurn,
    QuicPacketNumberGap,
    QuicVersionNegotiationDecoy,
    QuicMultiInitialRealistic,
    IpFrag2Udp,
}

impl UdpChainStepKind {
    pub const fn emitter_tier(self) -> EmitterTier {
        match self {
            Self::IpFrag2Udp => EmitterTier::RootedProduction,
            Self::FakeBurst
            | Self::DummyPrepend
            | Self::QuicSniSplit
            | Self::QuicFakeVersion
            | Self::QuicCryptoSplit
            | Self::QuicPaddingLadder
            | Self::QuicCidChurn
            | Self::QuicPacketNumberGap
            | Self::QuicVersionNegotiationDecoy
            | Self::QuicMultiInitialRealistic => EmitterTier::NonRootProduction,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpChainStep {
    pub kind: UdpChainStepKind,
    pub count: i32,
    pub split_bytes: i32,
    pub activation_filter: Option<ActivationFilter>,
    /// Send IP fragments in reverse order (second before first).
    pub ip_frag_disorder: bool,
    /// Insert IPv6 Hop-by-Hop Options extension header (no-op for IPv4).
    pub ipv6_hop_by_hop: bool,
    /// Insert IPv6 Destination Options header in unfragmentable part.
    pub ipv6_dest_opt: bool,
    /// Insert IPv6 Destination Options header in fragmentable part.
    pub ipv6_dest_opt2: bool,
    /// Override second fragment's next_header (IPv6 only).
    pub ipv6_frag_next_override: Option<u8>,
}

impl UdpChainStep {
    pub const fn new(kind: UdpChainStepKind, count: i32) -> Self {
        Self {
            kind,
            count,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        }
    }
}
