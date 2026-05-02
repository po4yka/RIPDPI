//! Private probe-adapter namespaces for the monitor engine.
//!
//! The engine still links concrete diagnostics lane crates because it is the
//! orchestration crate, but those imports stay behind this private module
//! instead of being re-exported as a public aggregate facade.

pub(crate) mod blockpage_fingerprints {
    pub(crate) use ripdpi_diagnostics_http::blockpage_fingerprints::*;
}

pub(crate) mod candidates {
    pub(crate) use ripdpi_diagnostics_candidates::candidates::*;
}

pub(crate) mod cdn_ech {
    pub(crate) use ripdpi_diagnostics_dns::cdn_ech::*;
}

pub(crate) mod classification {
    pub(crate) use ripdpi_diagnostics_classification::classification::*;
}

pub(crate) mod connectivity {
    pub(crate) use ripdpi_diagnostics_runner::connectivity::*;
}

pub(crate) mod http {
    pub(crate) use ripdpi_diagnostics_http::http::*;
}

pub(crate) mod observations {
    pub(crate) use ripdpi_diagnostics_classification::observations::*;
}

pub(crate) mod strategy {
    pub(crate) use ripdpi_diagnostics_runner::strategy::*;
}

pub(crate) mod telegram {
    pub(crate) use ripdpi_diagnostics_telegram::telegram::*;
}

pub(crate) mod tls {
    pub(crate) use ripdpi_diagnostics_tls::tls::*;
}

pub(crate) mod transport {
    pub(crate) use ripdpi_diagnostics_transport::transport::*;
}

pub(crate) mod util {
    pub(crate) use ripdpi_diagnostics_contracts::util::*;
}
