pub mod candidates;

pub use ripdpi_diagnostics_contracts::*;
pub use ripdpi_diagnostics_net::{dns, util};

pub mod types {
    pub use ripdpi_diagnostics_contracts::*;
}

pub mod wire {
    pub use ripdpi_diagnostics_contracts::wire::*;
}

#[cfg(test)]
mod candidates_tests;
