mod fake;
mod flags;
mod fragments;
mod seq_overlap;
mod tls_prelude;

use super::{TcpChainStep, TcpChainStepKind};

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

impl TcpChainStep {
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
}
