pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}

pub(crate) mod dns_analysis {
    pub use ripdpi_diagnostics_dns::dns_analysis::*;
}

pub(crate) mod dns_oracle {
    pub use crate::shared_adapters::dns_oracle::*;
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
    pub use crate::shared_adapters::transport::*;
}

pub(crate) mod util {
    pub use crate::shared_adapters::util::*;
}
