pub(crate) mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::{
        StrategyProbeBaseline, strategy_probe_encrypted_dns_context, strategy_probe_encrypted_dns_endpoint,
        strategy_probe_encrypted_dns_label,
    };
}

pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::resolve_via_encrypted_dns;
}

pub(crate) mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::{
        DnsOracleAssessment, DnsOracleConfig, DnsOracleResponse, evaluate_dns_oracles,
    };
}

pub(crate) mod transport {
    pub use ripdpi_diagnostics_transport::transport::{
        DnsResolveError, TargetAddress, domain_connect_target, resolve_addresses,
    };
}

pub(crate) mod util {
    pub use ripdpi_diagnostics_contracts::util::{DnsAnswerOverlap, classify_dns_answer_overlap};
}
