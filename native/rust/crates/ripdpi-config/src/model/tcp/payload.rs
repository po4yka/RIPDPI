mod fake;
mod flags;
mod fragments;
mod seq_overlap;
mod tls_prelude;

use std::fmt;

use super::{ActivationFilter, OffsetExpr, SeqOverlapFakeMode, TcpChainStep, TcpChainStepKind};

pub use fake::{TcpFakeOrdering, TcpFakePayload, TcpHostFakePayload};
pub use flags::TcpFlagOverrides;
pub use fragments::{TcpIpFragPayload, TcpIpv6ExtensionPayload};
pub use seq_overlap::TcpSeqOverlapPayload;
pub use tls_prelude::TcpTlsRandRecPayload;

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpStepPayloadInvariantError {
    kind: TcpChainStepKind,
    field: &'static str,
}

impl TcpStepPayloadInvariantError {
    const fn new(kind: TcpChainStepKind, field: &'static str) -> Self {
        Self { kind, field }
    }

    pub const fn kind(&self) -> TcpChainStepKind {
        self.kind
    }

    pub const fn field(&self) -> &'static str {
        self.field
    }
}

impl fmt::Display for TcpStepPayloadInvariantError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{:?} must not declare {} payload fields", self.kind, self.field)
    }
}

impl std::error::Error for TcpStepPayloadInvariantError {}

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

    pub fn validate_payload_family(&self) -> Result<(), TcpStepPayloadInvariantError> {
        if !self.kind.supports_fake_ordering() && self.fake_ordering() != TcpFakeOrdering::before_each_duplicate() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "fake ordering"));
        }
        if !self.kind.supports_fake_tcp_flags() && self.fake_flag_overrides() != TcpFlagOverrides::disabled() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "fake TCP flags"));
        }
        if !self.kind.supports_orig_tcp_flags() && self.original_flag_overrides() != TcpFlagOverrides::disabled() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "original TCP flags"));
        }
        if self.kind != TcpChainStepKind::SeqOverlap && self.seq_overlap_storage_active() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "sequence-overlap"));
        }
        if self.kind != TcpChainStepKind::HostFake && self.hostfake_storage_active() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "hostfake"));
        }
        if self.kind != TcpChainStepKind::TlsRandRec
            && self.kind != TcpChainStepKind::IpFrag2
            && self.fragment_storage_active()
        {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "fragmentation"));
        }
        if self.kind != TcpChainStepKind::IpFrag2 && self.ipv6_extension_payload() != TcpIpv6ExtensionPayload::default()
        {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "IPv6 fragmentation"));
        }
        Ok(())
    }

    const fn common_payload(&self) -> TcpStepCommon {
        TcpStepCommon {
            offset: self.offset,
            activation_filter: self.activation_filter,
            inter_segment_delay_ms: self.inter_segment_delay_ms,
        }
    }

    pub fn with_seq_overlap_payload(mut self, payload: TcpSeqOverlapPayload) -> Self {
        self.apply_seq_overlap_payload(payload);
        self
    }

    pub fn apply_seq_overlap_payload(&mut self, payload: TcpSeqOverlapPayload) {
        self.overlap_size = payload.overlap_size;
        self.seqovl_fake_mode = payload.fake_mode;
        self.tcp_flags_set = payload.fake_flags.set;
        self.tcp_flags_unset = payload.fake_flags.unset;
    }

    pub fn seq_overlap_payload(&self) -> Option<TcpSeqOverlapPayload> {
        if self.kind == TcpChainStepKind::SeqOverlap {
            Some(TcpSeqOverlapPayload {
                overlap_size: self.overlap_size,
                fake_mode: self.seqovl_fake_mode,
                fake_flags: self.fake_flag_overrides(),
            })
        } else {
            None
        }
    }

    pub fn with_fake_payload(mut self, payload: TcpFakePayload) -> Self {
        self.apply_fake_payload(payload);
        self
    }

    pub fn apply_fake_payload(&mut self, payload: TcpFakePayload) {
        self.fake_order = payload.ordering.order;
        self.fake_seq_mode = payload.ordering.seq_mode;
        self.tcp_flags_set = payload.fake_flags.set;
        self.tcp_flags_unset = payload.fake_flags.unset;
        self.tcp_flags_orig_set = payload.original_flags.set;
        self.tcp_flags_orig_unset = payload.original_flags.unset;
    }

    pub fn fake_payload(&self) -> Option<TcpFakePayload> {
        if self.kind.supports_fake_ordering() {
            Some(TcpFakePayload {
                ordering: self.fake_ordering(),
                fake_flags: self.fake_flag_overrides(),
                original_flags: self.original_flag_overrides(),
            })
        } else {
            None
        }
    }

    pub fn host_fake_payload(&self) -> Option<TcpHostFakePayload<'_>> {
        if self.kind == TcpChainStepKind::HostFake {
            Some(TcpHostFakePayload {
                midhost_offset: self.midhost_offset,
                fake_host_template: self.fake_host_template.as_deref(),
                random_fake_host: self.random_fake_host,
                ordering: self.fake_ordering(),
                fake_flags: self.fake_flag_overrides(),
                original_flags: self.original_flag_overrides(),
            })
        } else {
            None
        }
    }

    pub fn with_tls_randrec_payload(mut self, payload: TcpTlsRandRecPayload) -> Self {
        self.apply_tls_randrec_payload(payload);
        self
    }

    pub fn apply_tls_randrec_payload(&mut self, payload: TcpTlsRandRecPayload) {
        self.fragment_count = payload.fragment_count;
        self.min_fragment_size = payload.min_fragment_size;
        self.max_fragment_size = payload.max_fragment_size;
    }

    pub fn tls_randrec_payload(&self) -> Option<TcpTlsRandRecPayload> {
        if self.kind == TcpChainStepKind::TlsRandRec {
            Some(TcpTlsRandRecPayload {
                fragment_count: self.fragment_count,
                min_fragment_size: self.min_fragment_size,
                max_fragment_size: self.max_fragment_size,
            })
        } else {
            None
        }
    }

    pub fn with_ip_frag_payload(mut self, payload: TcpIpFragPayload) -> Self {
        self.apply_ip_frag_payload(payload);
        self
    }

    pub fn apply_ip_frag_payload(&mut self, payload: TcpIpFragPayload) {
        self.fragment_count = payload.fragment_count;
        self.min_fragment_size = payload.min_fragment_size;
        self.max_fragment_size = payload.max_fragment_size;
        self.ip_frag_disorder = payload.disorder;
        self.ipv6_hop_by_hop = payload.ipv6_extensions.hop_by_hop;
        self.ipv6_dest_opt = payload.ipv6_extensions.dest_opt;
        self.ipv6_dest_opt2 = payload.ipv6_extensions.dest_opt2;
        self.ipv6_routing = payload.ipv6_extensions.routing;
        self.ipv6_frag_next_override = payload.ipv6_extensions.second_frag_next_override;
    }

    pub fn ip_frag_payload(&self) -> Option<TcpIpFragPayload> {
        if self.kind == TcpChainStepKind::IpFrag2 {
            Some(TcpIpFragPayload {
                fragment_count: self.fragment_count,
                min_fragment_size: self.min_fragment_size,
                max_fragment_size: self.max_fragment_size,
                disorder: self.ip_frag_disorder,
                ipv6_extensions: self.ipv6_extension_payload(),
            })
        } else {
            None
        }
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

    pub const fn fake_ordering(&self) -> TcpFakeOrdering {
        TcpFakeOrdering { order: self.fake_order, seq_mode: self.fake_seq_mode }
    }

    pub const fn fake_flag_overrides(&self) -> TcpFlagOverrides {
        TcpFlagOverrides { set: self.tcp_flags_set, unset: self.tcp_flags_unset }
    }

    pub const fn original_flag_overrides(&self) -> TcpFlagOverrides {
        TcpFlagOverrides { set: self.tcp_flags_orig_set, unset: self.tcp_flags_orig_unset }
    }

    pub const fn ipv6_extension_payload(&self) -> TcpIpv6ExtensionPayload {
        TcpIpv6ExtensionPayload {
            hop_by_hop: self.ipv6_hop_by_hop,
            dest_opt: self.ipv6_dest_opt,
            dest_opt2: self.ipv6_dest_opt2,
            routing: self.ipv6_routing,
            second_frag_next_override: self.ipv6_frag_next_override,
        }
    }

    const fn seq_overlap_storage_active(&self) -> bool {
        self.overlap_size != 0 || !matches!(self.seqovl_fake_mode, SeqOverlapFakeMode::Profile)
    }

    fn hostfake_storage_active(&self) -> bool {
        self.midhost_offset.is_some() || self.fake_host_template.is_some() || self.random_fake_host
    }

    const fn fragment_storage_active(&self) -> bool {
        self.fragment_count != 0 || self.min_fragment_size != 0 || self.max_fragment_size != 0 || self.ip_frag_disorder
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::{FakeOrder, FakeSeqMode, OffsetBase, OffsetExpr, SeqOverlapFakeMode};

    #[test]
    fn seq_overlap_payload_round_trips_through_compatible_fields() {
        let payload = TcpSeqOverlapPayload {
            overlap_size: 16,
            fake_mode: SeqOverlapFakeMode::Rand,
            fake_flags: TcpFlagOverrides { set: Some(0x18), unset: Some(0x02) },
        };
        let step = TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::marker(OffsetBase::Host, 2))
            .with_seq_overlap_payload(payload);

        assert_eq!(step.seq_overlap_payload(), Some(payload));
        assert_eq!(step.overlap_size, 16);
        assert_eq!(step.seqovl_fake_mode, SeqOverlapFakeMode::Rand);
        assert_eq!(step.tcp_flags_set, Some(0x18));
        assert_eq!(step.tcp_flags_unset, Some(0x02));
    }

    #[test]
    fn payload_accessors_are_variant_specific() {
        let step = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(3));

        assert_eq!(step.seq_overlap_payload(), None);
        assert_eq!(step.host_fake_payload(), None);
        assert_eq!(step.tls_randrec_payload(), None);
        assert_eq!(step.ip_frag_payload(), None);
    }

    #[test]
    fn host_fake_payload_borrows_compatible_fields() {
        let mut step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::Host, 1));
        step.midhost_offset = Some(OffsetExpr::marker(OffsetBase::HostMid, 0));
        step.fake_host_template = Some("cdn.example".to_string());
        step.fake_order = FakeOrder::AllFakesFirst;
        step.fake_seq_mode = FakeSeqMode::Sequential;
        step.random_fake_host = true;

        let payload = step.host_fake_payload().expect("hostfake payload");

        assert_eq!(payload.midhost_offset, Some(OffsetExpr::marker(OffsetBase::HostMid, 0)));
        assert_eq!(payload.fake_host_template, Some("cdn.example"));
        assert!(payload.random_fake_host);
        assert_eq!(
            payload.ordering,
            TcpFakeOrdering { order: FakeOrder::AllFakesFirst, seq_mode: FakeSeqMode::Sequential }
        );
    }

    #[test]
    fn ip_frag_payload_groups_fragment_and_ipv6_fields() {
        let payload = TcpIpFragPayload {
            fragment_count: 2,
            min_fragment_size: 8,
            max_fragment_size: 32,
            disorder: true,
            ipv6_extensions: TcpIpv6ExtensionPayload {
                hop_by_hop: true,
                dest_opt: true,
                dest_opt2: false,
                routing: true,
                second_frag_next_override: Some(6),
            },
        };
        let step = TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(5)).with_ip_frag_payload(payload);

        assert_eq!(step.ip_frag_payload(), Some(payload));
        assert!(step.ip_frag_disorder);
        assert!(step.ipv6_hop_by_hop);
        assert!(step.ipv6_dest_opt);
        assert!(step.ipv6_routing);
        assert_eq!(step.ipv6_frag_next_override, Some(6));
    }

    #[test]
    fn typed_payload_separates_step_families() {
        let seq = TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::absolute(3));
        assert!(matches!(seq.typed_payload(), TcpStepPayload::SeqOverlap(_)));

        let split = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(3));
        assert_eq!(split.typed_payload(), TcpStepPayload::Plain);
    }

    #[test]
    fn typed_step_rejects_fake_ordering_on_plain_step() {
        let mut step = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(3));
        step.set_fake_ordering(TcpFakeOrdering { order: FakeOrder::AllFakesFirst, seq_mode: FakeSeqMode::Sequential });

        let error = step.try_typed_step().expect_err("invalid fake ordering");

        assert_eq!(error.kind(), TcpChainStepKind::Split);
        assert_eq!(error.field(), "fake ordering");
    }

    #[test]
    fn typed_step_rejects_hostfake_payload_on_fake_step() {
        let step = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(3))
            .with_fake_host_template(Some("example.invalid".to_string()));

        let error = step.try_typed_step().expect_err("invalid hostfake payload");

        assert_eq!(error.kind(), TcpChainStepKind::Fake);
        assert_eq!(error.field(), "hostfake");
    }

    #[test]
    fn typed_step_projects_ipfrag_without_other_payload_families() {
        let payload = TcpIpFragPayload {
            fragment_count: 2,
            min_fragment_size: 8,
            max_fragment_size: 32,
            disorder: true,
            ipv6_extensions: TcpIpv6ExtensionPayload { hop_by_hop: true, ..Default::default() },
        };
        let step = TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(3)).with_ip_frag_payload(payload);

        let typed = step.try_typed_step().expect("typed ipfrag");

        assert!(matches!(typed, TcpTypedChainStep::IpFrag { payload: typed_payload, .. } if typed_payload == payload));
    }
}
