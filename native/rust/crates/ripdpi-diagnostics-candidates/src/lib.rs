pub mod candidates;

pub use ripdpi_diagnostics_contracts::*;

pub mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}

pub mod util {
    pub use ripdpi_diagnostics_contracts::util::*;
}

pub mod types {
    pub use ripdpi_diagnostics_contracts::*;
}

pub mod wire {
    pub use ripdpi_diagnostics_contracts::wire::*;
}

#[cfg(test)]
mod candidates_tests;
