pub mod connectivity;
pub mod domain;
pub mod strategy;

mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}
mod dns_analysis {
    pub use ripdpi_diagnostics_dns::dns_analysis::*;
}
mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::*;
}
mod fat_header {
    pub use ripdpi_diagnostics_fat_header::fat_header::*;
}
mod http {
    pub use ripdpi_diagnostics_http::http::*;
}
mod tls {
    pub use ripdpi_diagnostics_tls::tls::*;
}
mod transport {
    pub use ripdpi_diagnostics_transport::transport::*;
}
mod util {
    pub use ripdpi_diagnostics_contracts::util::*;
}

mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::*;
}

mod types {
    pub use ripdpi_diagnostics_contracts::*;
}

/// Compatibility surface for callers that historically treated the runner as
/// the diagnostics aggregate facade. New code should depend on the narrow
/// diagnostics crates directly.
pub mod compatibility {
    pub use ripdpi_diagnostics_contracts::*;

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
}

#[cfg(test)]
pub mod test_fixtures;
