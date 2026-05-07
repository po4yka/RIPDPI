use super::{
    TcpFakePayload, TcpFlagOverrides, TcpHostFakePayload, TcpIpFragPayload, TcpSeqOverlapPayload,
    TcpStepPayloadInvariantError, TcpTlsRandRecPayload,
};
use crate::{ActivationFilter, OffsetExpr, TcpChainStep, TcpChainStepKind, TcpFakeOrdering};

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

impl TcpStepCommon {
    pub const fn new(offset: OffsetExpr) -> Self {
        Self { offset, activation_filter: None, inter_segment_delay_ms: 0 }
    }
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum TcpStepPayloadStorage {
    Plain { kind: TcpChainStepKind, original_flags: TcpFlagOverrides },
    SeqOverlap(TcpSeqOverlapPayload),
    Fake { kind: TcpChainStepKind, payload: TcpFakePayload },
    HostFake(TcpHostFakePayloadStorage),
    TlsRandRec(TcpTlsRandRecPayload),
    IpFrag { payload: TcpIpFragPayload, original_flags: TcpFlagOverrides },
    FakeRst { fake_flags: TcpFlagOverrides },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TcpHostFakePayloadStorage {
    midhost_offset: Option<OffsetExpr>,
    fake_host_template: Option<String>,
    random_fake_host: bool,
    ordering: TcpFakeOrdering,
    fake_flags: TcpFlagOverrides,
    original_flags: TcpFlagOverrides,
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

impl TcpStepPayloadStorage {
    pub(crate) const fn default_for_kind(kind: TcpChainStepKind) -> Self {
        match kind {
            TcpChainStepKind::SeqOverlap => Self::SeqOverlap(TcpSeqOverlapPayload::profile(0)),
            TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder => Self::Fake {
                kind,
                payload: TcpFakePayload {
                    ordering: TcpFakeOrdering::before_each_duplicate(),
                    fake_flags: TcpFlagOverrides::disabled(),
                    original_flags: TcpFlagOverrides::disabled(),
                },
            },
            TcpChainStepKind::HostFake => Self::HostFake(TcpHostFakePayloadStorage {
                midhost_offset: None,
                fake_host_template: None,
                random_fake_host: false,
                ordering: TcpFakeOrdering::before_each_duplicate(),
                fake_flags: TcpFlagOverrides::disabled(),
                original_flags: TcpFlagOverrides::disabled(),
            }),
            TcpChainStepKind::TlsRandRec => {
                Self::TlsRandRec(TcpTlsRandRecPayload { fragment_count: 0, min_fragment_size: 0, max_fragment_size: 0 })
            }
            TcpChainStepKind::IpFrag2 => Self::IpFrag {
                payload: TcpIpFragPayload {
                    fragment_count: 0,
                    min_fragment_size: 0,
                    max_fragment_size: 0,
                    disorder: false,
                    ipv6_extensions: super::TcpIpv6ExtensionPayload {
                        hop_by_hop: false,
                        dest_opt: false,
                        dest_opt2: false,
                        routing: false,
                        second_frag_next_override: None,
                    },
                },
                original_flags: TcpFlagOverrides::disabled(),
            },
            TcpChainStepKind::FakeRst => Self::FakeRst { fake_flags: TcpFlagOverrides::disabled() },
            TcpChainStepKind::Split
            | TcpChainStepKind::SynData
            | TcpChainStepKind::Disorder
            | TcpChainStepKind::MultiDisorder
            | TcpChainStepKind::Oob
            | TcpChainStepKind::Disoob
            | TcpChainStepKind::TlsRec => Self::Plain { kind, original_flags: TcpFlagOverrides::disabled() },
        }
    }

    pub(crate) const fn kind(&self) -> TcpChainStepKind {
        match self {
            Self::Plain { kind, .. } | Self::Fake { kind, .. } => *kind,
            Self::SeqOverlap(_) => TcpChainStepKind::SeqOverlap,
            Self::HostFake(_) => TcpChainStepKind::HostFake,
            Self::TlsRandRec(_) => TcpChainStepKind::TlsRandRec,
            Self::IpFrag { .. } => TcpChainStepKind::IpFrag2,
            Self::FakeRst { .. } => TcpChainStepKind::FakeRst,
        }
    }

    pub(crate) const fn midhost_offset(&self) -> Option<OffsetExpr> {
        match self {
            Self::HostFake(payload) => payload.midhost_offset,
            _ => None,
        }
    }

    pub(crate) fn set_midhost_offset(&mut self, midhost_offset: Option<OffsetExpr>) {
        if let Self::HostFake(payload) = self {
            payload.midhost_offset = midhost_offset;
        }
    }

    pub(crate) fn fake_host_template(&self) -> Option<&str> {
        match self {
            Self::HostFake(payload) => payload.fake_host_template.as_deref(),
            _ => None,
        }
    }

    pub(crate) fn set_fake_host_template(&mut self, fake_host_template: Option<String>) {
        if let Self::HostFake(payload) = self {
            payload.fake_host_template = fake_host_template;
        }
    }

    pub(crate) const fn random_fake_host(&self) -> bool {
        match self {
            Self::HostFake(payload) => payload.random_fake_host,
            _ => false,
        }
    }

    pub(crate) fn set_random_fake_host(&mut self, random_fake_host: bool) {
        if let Self::HostFake(payload) = self {
            payload.random_fake_host = random_fake_host;
        }
    }

    pub(crate) const fn fake_ordering(&self) -> TcpFakeOrdering {
        match self {
            Self::Fake { payload, .. } => payload.ordering,
            Self::HostFake(payload) => payload.ordering,
            _ => TcpFakeOrdering::before_each_duplicate(),
        }
    }

    pub(crate) fn set_fake_ordering(&mut self, ordering: TcpFakeOrdering) {
        match self {
            Self::Fake { payload, .. } => payload.ordering = ordering,
            Self::HostFake(payload) => payload.ordering = ordering,
            _ => {}
        }
    }

    pub(crate) const fn fake_flag_overrides(&self) -> TcpFlagOverrides {
        match self {
            Self::SeqOverlap(payload) => payload.fake_flags,
            Self::Fake { payload, .. } => payload.fake_flags,
            Self::HostFake(payload) => payload.fake_flags,
            Self::FakeRst { fake_flags } => *fake_flags,
            _ => TcpFlagOverrides::disabled(),
        }
    }

    pub(crate) fn set_fake_flag_overrides(&mut self, flags: TcpFlagOverrides) {
        match self {
            Self::SeqOverlap(payload) => payload.fake_flags = flags,
            Self::Fake { payload, .. } => payload.fake_flags = flags,
            Self::HostFake(payload) => payload.fake_flags = flags,
            Self::FakeRst { fake_flags } => *fake_flags = flags,
            _ => {}
        }
    }

    pub(crate) const fn original_flag_overrides(&self) -> TcpFlagOverrides {
        match self {
            Self::Plain { original_flags, .. } | Self::IpFrag { original_flags, .. } => *original_flags,
            Self::Fake { payload, .. } => payload.original_flags,
            Self::HostFake(payload) => payload.original_flags,
            _ => TcpFlagOverrides::disabled(),
        }
    }

    pub(crate) fn set_original_flag_overrides(&mut self, flags: TcpFlagOverrides) {
        match self {
            Self::Plain { kind, original_flags } if kind.supports_orig_tcp_flags() => *original_flags = flags,
            Self::IpFrag { original_flags, .. } => *original_flags = flags,
            Self::Fake { payload, .. } => payload.original_flags = flags,
            Self::HostFake(payload) => payload.original_flags = flags,
            _ => {}
        }
    }

    pub(crate) fn host_fake_payload(&self) -> Option<TcpHostFakePayload<'_>> {
        match self {
            Self::HostFake(payload) => Some(TcpHostFakePayload {
                midhost_offset: payload.midhost_offset,
                fake_host_template: payload.fake_host_template.as_deref(),
                random_fake_host: payload.random_fake_host,
                ordering: payload.ordering,
                fake_flags: payload.fake_flags,
                original_flags: payload.original_flags,
            }),
            _ => None,
        }
    }

    pub(crate) fn hostfake_storage_active(&self) -> bool {
        matches!(
            self,
            Self::HostFake(
                TcpHostFakePayloadStorage { midhost_offset: Some(_), .. }
                    | TcpHostFakePayloadStorage { fake_host_template: Some(_), .. }
                    | TcpHostFakePayloadStorage { random_fake_host: true, .. },
            )
        )
    }

    pub(crate) const fn seq_overlap_payload(&self) -> Option<TcpSeqOverlapPayload> {
        match self {
            Self::SeqOverlap(payload) => Some(*payload),
            _ => None,
        }
    }

    pub(crate) fn set_seq_overlap_payload(&mut self, payload: TcpSeqOverlapPayload) {
        if let Self::SeqOverlap(stored) = self {
            *stored = payload;
        }
    }

    pub(crate) const fn seq_overlap_storage_active(&self) -> bool {
        match self {
            Self::SeqOverlap(payload) => {
                payload.overlap_size != 0 || !matches!(payload.fake_mode, crate::SeqOverlapFakeMode::Profile)
            }
            _ => false,
        }
    }

    pub(crate) const fn fake_payload(&self) -> Option<TcpFakePayload> {
        match self {
            Self::Fake { payload, .. } => Some(*payload),
            Self::HostFake(payload) => Some(TcpFakePayload {
                ordering: payload.ordering,
                fake_flags: payload.fake_flags,
                original_flags: payload.original_flags,
            }),
            _ => None,
        }
    }

    pub(crate) const fn tls_randrec_payload(&self) -> Option<TcpTlsRandRecPayload> {
        match self {
            Self::TlsRandRec(payload) => Some(*payload),
            _ => None,
        }
    }

    pub(crate) fn set_tls_randrec_payload(&mut self, payload: TcpTlsRandRecPayload) {
        if let Self::TlsRandRec(stored) = self {
            *stored = payload;
        }
    }

    pub(crate) const fn ip_frag_payload(&self) -> Option<TcpIpFragPayload> {
        match self {
            Self::IpFrag { payload, .. } => Some(*payload),
            _ => None,
        }
    }

    pub(crate) fn set_ip_frag_payload(&mut self, payload: TcpIpFragPayload) {
        if let Self::IpFrag { payload: stored, .. } = self {
            *stored = payload;
        }
    }

    pub(crate) const fn ipv6_extension_payload(&self) -> super::TcpIpv6ExtensionPayload {
        match self {
            Self::IpFrag { payload, .. } => payload.ipv6_extensions,
            _ => super::TcpIpv6ExtensionPayload {
                hop_by_hop: false,
                dest_opt: false,
                dest_opt2: false,
                routing: false,
                second_frag_next_override: None,
            },
        }
    }

    pub(crate) const fn fragment_storage_active(&self) -> bool {
        match self {
            Self::TlsRandRec(payload) => {
                payload.fragment_count != 0 || payload.min_fragment_size != 0 || payload.max_fragment_size != 0
            }
            Self::IpFrag { payload, .. } => {
                payload.fragment_count != 0
                    || payload.min_fragment_size != 0
                    || payload.max_fragment_size != 0
                    || payload.disorder
            }
            _ => false,
        }
    }
}

impl TcpChainStep {
    pub fn from_typed_step(step: TcpTypedChainStep<'_>) -> Self {
        match step {
            TcpTypedChainStep::Plain { kind, common, original_flags } => {
                Self { common, payload: TcpStepPayloadStorage::Plain { kind, original_flags } }
            }
            TcpTypedChainStep::SeqOverlap { common, payload } => {
                Self { common, payload: TcpStepPayloadStorage::SeqOverlap(payload) }
            }
            TcpTypedChainStep::Fake { kind, common, payload } => {
                Self { common, payload: TcpStepPayloadStorage::Fake { kind, payload } }
            }
            TcpTypedChainStep::HostFake { common, payload } => Self {
                common,
                payload: TcpStepPayloadStorage::HostFake(TcpHostFakePayloadStorage {
                    midhost_offset: payload.midhost_offset,
                    fake_host_template: payload.fake_host_template.map(str::to_owned),
                    random_fake_host: payload.random_fake_host,
                    ordering: payload.ordering,
                    fake_flags: payload.fake_flags,
                    original_flags: payload.original_flags,
                }),
            },
            TcpTypedChainStep::TlsRandRec { common, payload } => {
                Self { common, payload: TcpStepPayloadStorage::TlsRandRec(payload) }
            }
            TcpTypedChainStep::IpFrag { common, payload, original_flags } => {
                Self { common, payload: TcpStepPayloadStorage::IpFrag { payload, original_flags } }
            }
            TcpTypedChainStep::FakeRst { common, fake_flags } => {
                Self { common, payload: TcpStepPayloadStorage::FakeRst { fake_flags } }
            }
        }
    }

    pub fn typed_step(&self) -> TcpTypedChainStep<'_> {
        let common = self.common_payload();
        match &self.payload {
            TcpStepPayloadStorage::Plain { kind, original_flags } => {
                TcpTypedChainStep::Plain { kind: *kind, common, original_flags: *original_flags }
            }
            TcpStepPayloadStorage::SeqOverlap(payload) => TcpTypedChainStep::SeqOverlap { common, payload: *payload },
            TcpStepPayloadStorage::Fake { kind, payload } => {
                TcpTypedChainStep::Fake { kind: *kind, common, payload: *payload }
            }
            TcpStepPayloadStorage::HostFake(_) => TcpTypedChainStep::HostFake {
                common,
                payload: self.host_fake_payload().expect("hostfake storage variant"),
            },
            TcpStepPayloadStorage::TlsRandRec(payload) => TcpTypedChainStep::TlsRandRec { common, payload: *payload },
            TcpStepPayloadStorage::IpFrag { payload, original_flags } => {
                TcpTypedChainStep::IpFrag { common, payload: *payload, original_flags: *original_flags }
            }
            TcpStepPayloadStorage::FakeRst { fake_flags } => {
                TcpTypedChainStep::FakeRst { common, fake_flags: *fake_flags }
            }
        }
    }

    pub fn try_typed_step(&self) -> Result<TcpTypedChainStep<'_>, TcpStepPayloadInvariantError> {
        self.validate_payload_family()?;
        Ok(self.typed_step())
    }

    pub fn typed_payload(&self) -> TcpStepPayload<'_> {
        match &self.payload {
            TcpStepPayloadStorage::SeqOverlap(payload) => TcpStepPayload::SeqOverlap(*payload),
            TcpStepPayloadStorage::Fake { payload, .. } => TcpStepPayload::Fake(*payload),
            TcpStepPayloadStorage::HostFake(_) => {
                TcpStepPayload::HostFake(self.host_fake_payload().expect("hostfake storage variant"))
            }
            TcpStepPayloadStorage::TlsRandRec(payload) => TcpStepPayload::TlsRandRec(*payload),
            TcpStepPayloadStorage::IpFrag { payload, .. } => TcpStepPayload::IpFrag(*payload),
            TcpStepPayloadStorage::Plain { .. } | TcpStepPayloadStorage::FakeRst { .. } => TcpStepPayload::Plain,
        }
    }

    const fn common_payload(&self) -> TcpStepCommon {
        self.common
    }
}
