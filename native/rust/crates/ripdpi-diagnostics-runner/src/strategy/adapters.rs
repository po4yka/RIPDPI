pub(crate) mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::*;
}

pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}

pub(crate) mod dns_oracle {
    pub use crate::shared_adapters::dns_oracle::*;
}

pub(crate) mod transport {
    pub use crate::shared_adapters::transport::*;
}

pub(crate) mod util {
    pub use crate::shared_adapters::util::*;
}
