use ripdpi_config::{ActivationFilter, FakeOrder, FakeSeqMode, OffsetExpr, TcpChainStep, TcpChainStepKind};

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
        TcpChainStep {
            kind: self.kind,
            offset: self.offset,
            activation_filter: self.activation_filter,
            midhost_offset: self.midhost_offset,
            fake_host_template: self.fake_host_template,
            fake_order: self.fake_order,
            fake_seq_mode: self.fake_seq_mode,
            tcp_flags_set: self.tcp_flags.set,
            tcp_flags_unset: self.tcp_flags.unset,
            tcp_flags_orig_set: self.tcp_flags.orig_set,
            tcp_flags_orig_unset: self.tcp_flags.orig_unset,
            overlap_size: self.seq_overlap.overlap_size,
            seqovl_fake_mode: self.seq_overlap.fake_mode,
            fragment_count: self.tlsrandrec.fragment_count,
            min_fragment_size: self.tlsrandrec.min_fragment_size,
            max_fragment_size: self.tlsrandrec.max_fragment_size,
            inter_segment_delay_ms: self.inter_segment_delay_ms.min(500),
            ip_frag_disorder: false,
            ipv6_hop_by_hop: self.ipv6_ext.hop_by_hop,
            ipv6_dest_opt: self.ipv6_ext.dest_opt,
            ipv6_dest_opt2: self.ipv6_ext.dest_opt2,
            ipv6_routing: false,
            ipv6_frag_next_override: None,
            random_fake_host: self.random_fake_host,
        }
    }
}
