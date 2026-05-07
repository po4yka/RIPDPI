pub(crate) mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::*;
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
