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

pub mod desync_model {
    pub use ripdpi_desync::{
        ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
        AdaptiveUdpBurstProfile, TcpSegmentHint,
    };
}

pub mod config {
    pub use ripdpi_config::*;
}

pub mod protocol_payload;
pub mod raw_packet_requirements;

pub mod proxy_config {
    pub use ripdpi_proxy_config::*;
}

pub mod runtime_api {
    pub use ripdpi_runtime_api::*;
}

pub mod services {
    pub use ripdpi_runtime_services::{ServicesState, ServicesStateHandle};
}

pub mod session {
    pub use ripdpi_session::*;
}

pub mod udp_desync;

pub mod ws_bootstrap {
    pub use ripdpi_ws_bootstrap::*;
}

mod sync {
    #[cfg(feature = "loom")]
    pub(crate) use loom::sync::atomic::AtomicBool;
    #[cfg(not(feature = "loom"))]
    pub(crate) use std::sync::atomic::AtomicBool;
}
