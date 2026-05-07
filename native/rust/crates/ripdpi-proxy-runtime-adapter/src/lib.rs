#![forbid(unsafe_code)]

pub mod failure {
    pub use ripdpi_failure_classifier::*;
}

pub mod platform {
    pub use ripdpi_runtime_platform::*;
}

pub mod ip_fragmentation {
    pub use ripdpi_ipfrag::*;
}

pub mod desync_platform;
pub mod protocol_payload;
pub mod raw_packet_requirements;

pub mod ws_bootstrap {
    pub use ripdpi_ws_bootstrap::*;
}

mod sync {
    #[cfg(feature = "loom")]
    pub(crate) use loom::sync::atomic::AtomicBool;
    #[cfg(not(feature = "loom"))]
    pub(crate) use std::sync::atomic::AtomicBool;
}
