pub(in crate::engine::runners::strategy) struct AuditSignals {
    pub(in crate::engine::runners::strategy) dns_tampering_with_fallback: bool,
    pub(in crate::engine::runners::strategy) weak_winner_coverage: bool,
    pub(in crate::engine::runners::strategy) low_tcp_execution: bool,
    pub(in crate::engine::runners::strategy) low_quic_execution: bool,
    pub(in crate::engine::runners::strategy) narrow_tcp_margin: bool,
    pub(in crate::engine::runners::strategy) narrow_quic_margin: bool,
    pub(in crate::engine::runners::strategy) all_tcp_tied: bool,
    pub(in crate::engine::runners::strategy) all_quic_tied: bool,
}

mod build;

pub(in crate::engine::runners::strategy) use build::build_audit_confidence;
