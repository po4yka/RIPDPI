use super::{
    TcpFakePayload, TcpFlagOverrides, TcpHostFakePayload, TcpIpFragPayload, TcpSeqOverlapPayload,
    TcpStepPayloadInvariantError, TcpTlsRandRecPayload,
};
use crate::{ActivationFilter, OffsetExpr, TcpChainStep, TcpChainStepKind};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TcpStepPayload<'a> {
    Plain,
    SeqOverlap(TcpSeqOverlapPayload),
    Fake(TcpFakePayload),
    HostFake(TcpHostFakePayload<'a>),
    TlsRandRec(TcpTlsRandRecPayload),
    IpFrag(TcpIpFragPayload),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpStepCommon {
    pub offset: OffsetExpr,
    pub activation_filter: Option<ActivationFilter>,
    pub inter_segment_delay_ms: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TcpTypedChainStep<'a> {
    Plain { kind: TcpChainStepKind, common: TcpStepCommon, original_flags: TcpFlagOverrides },
    SeqOverlap { common: TcpStepCommon, payload: TcpSeqOverlapPayload },
    Fake { kind: TcpChainStepKind, common: TcpStepCommon, payload: TcpFakePayload },
    HostFake { common: TcpStepCommon, payload: TcpHostFakePayload<'a> },
    TlsRandRec { common: TcpStepCommon, payload: TcpTlsRandRecPayload },
    IpFrag { common: TcpStepCommon, payload: TcpIpFragPayload, original_flags: TcpFlagOverrides },
    FakeRst { common: TcpStepCommon, fake_flags: TcpFlagOverrides },
}

impl TcpTypedChainStep<'_> {
    pub const fn common(&self) -> TcpStepCommon {
        match self {
            Self::Plain { common, .. }
            | Self::SeqOverlap { common, .. }
            | Self::Fake { common, .. }
            | Self::HostFake { common, .. }
            | Self::TlsRandRec { common, .. }
            | Self::IpFrag { common, .. }
            | Self::FakeRst { common, .. } => *common,
        }
    }

    pub const fn kind(&self) -> TcpChainStepKind {
        match self {
            Self::Plain { kind, .. } | Self::Fake { kind, .. } => *kind,
            Self::SeqOverlap { .. } => TcpChainStepKind::SeqOverlap,
            Self::HostFake { .. } => TcpChainStepKind::HostFake,
            Self::TlsRandRec { .. } => TcpChainStepKind::TlsRandRec,
            Self::IpFrag { .. } => TcpChainStepKind::IpFrag2,
            Self::FakeRst { .. } => TcpChainStepKind::FakeRst,
        }
    }
}

impl TcpChainStep {
    pub fn try_typed_step(&self) -> Result<TcpTypedChainStep<'_>, TcpStepPayloadInvariantError> {
        self.validate_payload_family()?;
        let common = self.common_payload();
        Ok(match self.kind {
            TcpChainStepKind::SeqOverlap => TcpTypedChainStep::SeqOverlap {
                common,
                payload: self.seq_overlap_payload().expect("validated seq overlap payload"),
            },
            TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder => {
                TcpTypedChainStep::Fake {
                    kind: self.kind,
                    common,
                    payload: self.fake_payload().expect("validated fake payload"),
                }
            }
            TcpChainStepKind::HostFake => TcpTypedChainStep::HostFake {
                common,
                payload: self.host_fake_payload().expect("validated hostfake payload"),
            },
            TcpChainStepKind::TlsRandRec => TcpTypedChainStep::TlsRandRec {
                common,
                payload: self.tls_randrec_payload().expect("validated tlsrandrec payload"),
            },
            TcpChainStepKind::IpFrag2 => TcpTypedChainStep::IpFrag {
                common,
                payload: self.ip_frag_payload().expect("validated ipfrag payload"),
                original_flags: self.original_flag_overrides(),
            },
            TcpChainStepKind::FakeRst => TcpTypedChainStep::FakeRst { common, fake_flags: self.fake_flag_overrides() },
            TcpChainStepKind::Split
            | TcpChainStepKind::SynData
            | TcpChainStepKind::Disorder
            | TcpChainStepKind::MultiDisorder
            | TcpChainStepKind::Oob
            | TcpChainStepKind::Disoob
            | TcpChainStepKind::TlsRec => {
                TcpTypedChainStep::Plain { kind: self.kind, common, original_flags: self.original_flag_overrides() }
            }
        })
    }

    pub fn typed_payload(&self) -> TcpStepPayload<'_> {
        match self.kind {
            TcpChainStepKind::SeqOverlap => {
                TcpStepPayload::SeqOverlap(self.seq_overlap_payload().expect("seq overlap payload"))
            }
            TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder => {
                TcpStepPayload::Fake(self.fake_payload().expect("fake payload"))
            }
            TcpChainStepKind::HostFake => TcpStepPayload::HostFake(self.host_fake_payload().expect("hostfake payload")),
            TcpChainStepKind::TlsRandRec => {
                TcpStepPayload::TlsRandRec(self.tls_randrec_payload().expect("tls randrec payload"))
            }
            TcpChainStepKind::IpFrag2 => TcpStepPayload::IpFrag(self.ip_frag_payload().expect("ip frag payload")),
            TcpChainStepKind::Split
            | TcpChainStepKind::SynData
            | TcpChainStepKind::Disorder
            | TcpChainStepKind::MultiDisorder
            | TcpChainStepKind::Oob
            | TcpChainStepKind::Disoob
            | TcpChainStepKind::TlsRec
            | TcpChainStepKind::FakeRst => TcpStepPayload::Plain,
        }
    }

    const fn common_payload(&self) -> TcpStepCommon {
        TcpStepCommon {
            offset: self.offset,
            activation_filter: self.activation_filter,
            inter_segment_delay_ms: self.inter_segment_delay_ms,
        }
    }
}
