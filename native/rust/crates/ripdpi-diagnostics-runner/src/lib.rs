pub mod connectivity;
pub mod domain;
pub mod strategy;

pub(crate) mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::*;
}
pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}
pub(crate) mod dns_analysis {
    pub use ripdpi_diagnostics_dns::dns_analysis::*;
}
pub(crate) mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::*;
}
pub(crate) mod fat_header {
    pub use ripdpi_diagnostics_fat_header::fat_header::*;
}
pub(crate) mod http {
    pub use ripdpi_diagnostics_http::http::*;
}
pub(crate) mod tls {
    pub use ripdpi_diagnostics_tls::tls::*;
}
pub(crate) mod transport {
    pub use ripdpi_diagnostics_transport::transport::*;
}
pub(crate) mod util {
    pub use ripdpi_diagnostics_contracts::util::{
        classify_dns_answer_overlap, find_headers_end, format_result_set, format_socket_result, ip_set,
        is_suspected_dns_tampering_outcome, now_ms, DnsAnswerOverlap, DEFAULT_DNS_SERVER, IO_TIMEOUT, MAX_HTTP_BYTES,
    };
    #[cfg(test)]
    pub use ripdpi_diagnostics_contracts::util::{CONNECT_TIMEOUT, FAT_HEADER_THRESHOLD_BYTES};
}

pub(crate) use ripdpi_diagnostics_contracts as types;

#[cfg(test)]
pub mod test_fixtures;
