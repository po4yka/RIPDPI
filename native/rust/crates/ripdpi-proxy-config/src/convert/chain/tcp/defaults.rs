use ripdpi_config::{
    FakeOrder, FakeSeqMode, OffsetBase, OffsetExpr, SeqOverlapFakeMode, TcpChainStep, TcpChainStepKind,
};

pub(crate) fn synthesize_tlsrec_prelude_for_bare_hostfake(chain: &mut Vec<TcpChainStep>) {
    let has_hostfake = chain.iter().any(|step| step.kind == TcpChainStepKind::HostFake);
    let has_tls_prelude = chain.iter().any(|step| step.kind.is_tls_prelude());
    if !has_hostfake || has_tls_prelude {
        return;
    }

    chain.insert(
        0,
        TcpChainStep {
            kind: TcpChainStepKind::TlsRec,
            offset: OffsetExpr::tls_marker(OffsetBase::ExtLen, 0),
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
        },
    );
}
