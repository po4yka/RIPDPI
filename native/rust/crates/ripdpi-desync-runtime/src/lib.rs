mod activation;
mod capability_policy;
mod emissions;
pub mod platform;
mod strategy_family;
mod sync {
    #[cfg(feature = "loom")]
    pub(crate) use loom::sync::atomic::{AtomicBool, Ordering};
    #[cfg(not(feature = "loom"))]
    pub(crate) use std::sync::atomic::{AtomicBool, Ordering};
}
mod tcp;
mod tcp_actions;
mod tcp_fake_family;
mod tcp_lowering;
mod tcp_plan;
mod transport_io;
mod types;

pub use activation::activation_context_from_progress;
pub use capability_policy::apply_tcp_capability_policy;
pub use strategy_family::primary_tcp_strategy_family;
pub use tcp::send_prepared_with_group;
pub use types::{OutboundSendError, OutboundSendOutcome, PcapHook};

pub const DESYNC_SEED_BASE: u32 = 7;
