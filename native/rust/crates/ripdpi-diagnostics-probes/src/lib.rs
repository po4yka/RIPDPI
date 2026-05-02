//! Compatibility facade for external diagnostics-probe consumers.
//!
//! New in-workspace code should depend on the narrower `ripdpi-diagnostics-*` crates directly.
//! This crate remains as an external compatibility surface and keeps the historic root exports
//! available through the default `compat-facade` feature.

/// External-only compatibility namespace for callers that still need the aggregate probes API.
///
/// Internal crates should import protocol-specific crates directly instead of routing through this
/// namespace. The root-level re-exports below mirror this module for source compatibility while the
/// default `compat-facade` feature is enabled.
pub mod compat {
    pub use ripdpi_diagnostics_runner::{connectivity, domain, strategy};

    pub mod blockpage_fingerprints {
        pub use ripdpi_diagnostics_http::blockpage_fingerprints::*;
    }
    pub mod cdn_ech {
        pub use ripdpi_diagnostics_dns::cdn_ech::*;
    }
    pub mod dns {
        pub use ripdpi_diagnostics_dns::dns::*;
    }
    pub mod dns_analysis {
        pub use ripdpi_diagnostics_dns::dns_analysis::*;
    }
    pub mod dns_oracle {
        pub use ripdpi_diagnostics_dns::dns_oracle::*;
    }
    pub mod fat_header {
        pub use ripdpi_diagnostics_fat_header::fat_header::*;
    }
    pub mod http {
        pub use ripdpi_diagnostics_http::http::*;
    }
    pub mod ja3 {
        pub use ripdpi_diagnostics_tls::ja3::*;
    }
    pub mod platform_ttl {
        pub use ripdpi_diagnostics_transport::platform_ttl::*;
    }
    pub mod telegram {
        pub use ripdpi_diagnostics_telegram::telegram::*;
    }
    pub mod tls {
        pub use ripdpi_diagnostics_tls::tls::*;
    }
    pub mod transport {
        pub use ripdpi_diagnostics_transport::transport::*;
    }
    pub mod util {
        pub use ripdpi_diagnostics_contracts::util::*;
    }

    pub mod candidates {
        pub use ripdpi_diagnostics_candidates::candidates::*;
    }

    pub mod classification {
        pub use ripdpi_diagnostics_classification::classification::*;
    }

    pub mod observations {
        pub use ripdpi_diagnostics_classification::observations::*;
    }

    pub mod types {
        pub use ripdpi_diagnostics_contracts::*;
    }

    pub mod wire {
        pub use ripdpi_diagnostics_contracts::wire::*;
    }

    pub use ripdpi_diagnostics_contracts::*;
}

#[cfg(feature = "compat-facade")]
#[doc(inline)]
pub use compat::*;
