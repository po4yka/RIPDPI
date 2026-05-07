#![forbid(unsafe_code)]

pub mod desync_platform;
pub mod failure;
pub mod ip_fragmentation;
pub mod model;
pub mod platform;
pub mod protocol_payload;
pub mod raw_packet_requirements;
pub mod response_triggers;
mod tcp_rotation;
pub mod udp_desync;
pub mod ws_bootstrap;

mod sync {
    #[cfg(feature = "loom")]
    pub(crate) use loom::sync::atomic::AtomicBool;
    #[cfg(not(feature = "loom"))]
    pub(crate) use std::sync::atomic::AtomicBool;
}
