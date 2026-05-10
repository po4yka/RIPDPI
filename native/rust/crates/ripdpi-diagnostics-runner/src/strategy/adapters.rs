pub(crate) mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::{
        strategy_probe_encrypted_dns_context, strategy_probe_encrypted_dns_endpoint,
        strategy_probe_encrypted_dns_label, StrategyProbeBaseline,
    };
}

pub(crate) mod dns {
    pub use ripdpi_diagnostics_protocols::dns::{build_fallback_encrypted_dns_endpoints, resolve_via_encrypted_dns};
}

pub(crate) mod dns_oracle {
    pub use ripdpi_diagnostics_protocols::dns_oracle::{evaluate_dns_oracles, DnsOracleAssessment, DnsOracleResponse};
}

pub(crate) mod transport {
    pub use ripdpi_diagnostics_protocols::transport::{
        direct_transport, domain_connect_target, resolve_addresses, TargetAddress,
    };
}

pub(crate) mod util {
    pub use ripdpi_diagnostics_protocols::util::{classify_dns_answer_overlap, DnsAnswerOverlap};
}
