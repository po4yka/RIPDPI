use ripdpi_config::{
    ActivationFilter, FakeOrder, FakeSeqMode, OffsetExpr, TcpChainStep, TcpChainStepKind, TcpFakeOrdering,
    TcpFlagOverrides, TcpIpFragPayload, TcpIpv6ExtensionPayload, TcpSeqOverlapPayload, TcpTlsRandRecPayload,
};

use crate::convert::chain::ipv6::ParsedIpv6ExtensionProfile;

use super::flags::ParsedTcpFlags;
use super::seq_overlap::ParsedSeqOverlap;
use super::tlsrandrec::ParsedTlsRandRec;

pub(crate) struct ParsedTcpChainStepFields {
    pub(crate) kind: TcpChainStepKind,
    pub(crate) offset: OffsetExpr,
    pub(crate) activation_filter: Option<ActivationFilter>,
    pub(crate) midhost_offset: Option<OffsetExpr>,
    pub(crate) fake_host_template: Option<String>,
    pub(crate) fake_order: FakeOrder,
    pub(crate) fake_seq_mode: FakeSeqMode,
    pub(crate) tcp_flags: ParsedTcpFlags,
    pub(crate) seq_overlap: ParsedSeqOverlap,
    pub(crate) tlsrandrec: ParsedTlsRandRec,
    pub(crate) inter_segment_delay_ms: u32,
    pub(crate) ipv6_ext: ParsedIpv6ExtensionProfile,
    pub(crate) random_fake_host: bool,
}

impl ParsedTcpChainStepFields {
    pub(crate) fn into_step(self) -> TcpChainStep {
        let fake_flags = TcpFlagOverrides { set: self.tcp_flags.set, unset: self.tcp_flags.unset };
        let original_flags = TcpFlagOverrides { set: self.tcp_flags.orig_set, unset: self.tcp_flags.orig_unset };
        let mut step = TcpChainStep::new(self.kind, self.offset)
            .with_activation_filter(self.activation_filter)
            .with_midhost_offset(self.midhost_offset)
            .with_fake_host_template(self.fake_host_template)
            .with_random_fake_host(self.random_fake_host)
            .with_inter_segment_delay_ms(self.inter_segment_delay_ms.min(500));

        step.set_fake_ordering(TcpFakeOrdering { order: self.fake_order, seq_mode: self.fake_seq_mode });
        step.set_fake_flag_overrides(fake_flags);
        step.set_original_flag_overrides(original_flags);

        match self.kind {
            TcpChainStepKind::SeqOverlap => {
                step.apply_seq_overlap_payload(TcpSeqOverlapPayload {
                    overlap_size: self.seq_overlap.overlap_size,
                    fake_mode: self.seq_overlap.fake_mode,
                    fake_flags,
                });
            }
            TcpChainStepKind::TlsRandRec => {
                step.apply_tls_randrec_payload(TcpTlsRandRecPayload {
                    fragment_count: self.tlsrandrec.fragment_count,
                    min_fragment_size: self.tlsrandrec.min_fragment_size,
                    max_fragment_size: self.tlsrandrec.max_fragment_size,
                });
            }
            TcpChainStepKind::IpFrag2 => {
                step.apply_ip_frag_payload(TcpIpFragPayload {
                    fragment_count: self.tlsrandrec.fragment_count,
                    min_fragment_size: self.tlsrandrec.min_fragment_size,
                    max_fragment_size: self.tlsrandrec.max_fragment_size,
                    disorder: false,
                    ipv6_extensions: TcpIpv6ExtensionPayload {
                        hop_by_hop: self.ipv6_ext.hop_by_hop,
                        dest_opt: self.ipv6_ext.dest_opt,
                        dest_opt2: self.ipv6_ext.dest_opt2,
                        routing: false,
                        second_frag_next_override: None,
                    },
                });
            }
            _ => {}
        }

        step
    }
}
