use ripdpi_config::{OffsetBase, OffsetExpr, TcpChainStep, TcpChainStepKind};

pub(crate) fn synthesize_tlsrec_prelude_for_bare_hostfake(chain: &mut Vec<TcpChainStep>) {
    let has_hostfake = chain.iter().any(|step| step.kind() == TcpChainStepKind::HostFake);
    let has_tls_prelude = chain.iter().any(|step| step.kind().is_tls_prelude());
    if !has_hostfake || has_tls_prelude {
        return;
    }

    chain.insert(0, TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::tls_marker(OffsetBase::ExtLen, 0)));
}
