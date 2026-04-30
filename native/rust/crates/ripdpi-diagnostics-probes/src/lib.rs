pub mod blockpage_fingerprints;
pub mod candidates;
pub mod cdn_ech;
pub mod classification;
pub mod connectivity;
pub mod dns;
pub mod dns_analysis;
pub mod dns_oracle;
pub mod domain;
pub mod fat_header;
pub mod http;
pub mod ja3;
pub mod observations;
pub mod platform_ttl;
pub mod strategy;
pub mod telegram;
pub mod tls;
pub mod transport;
pub mod util;

pub mod types {
    pub use ripdpi_diagnostics_contracts::*;
}

pub mod wire {
    pub use ripdpi_diagnostics_contracts::wire::*;
}

pub use ripdpi_diagnostics_contracts::*;

#[cfg(test)]
mod candidates_tests;
#[cfg(test)]
pub mod test_fixtures;
