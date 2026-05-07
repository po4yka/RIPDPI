pub(crate) mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::*;
}

pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}

pub(crate) mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::*;
}

pub(crate) mod transport {
    pub use ripdpi_diagnostics_transport::transport::*;
}

pub(crate) mod util {
    pub use ripdpi_diagnostics_contracts::util::{classify_dns_answer_overlap, DnsAnswerOverlap};
}
