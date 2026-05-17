#![forbid(unsafe_code)]

pub mod desync;
pub mod desync_platform;
pub mod failure;
pub mod ip_fragmentation;
pub mod model;
pub mod platform;
pub mod protocol_payload;
pub mod tcp_rotation;
pub mod udp_desync;

pub use ripdpi_desync_runtime::primary_tcp_strategy_family;

mod sync {
    #[cfg(feature = "loom")]
    pub(crate) use loom::sync::atomic::AtomicBool;
    #[cfg(not(feature = "loom"))]
    pub(crate) use std::sync::atomic::AtomicBool;
}
