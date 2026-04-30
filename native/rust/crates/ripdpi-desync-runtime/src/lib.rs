mod activation;
mod emissions;
pub mod platform;
mod sync {
    pub(crate) use std::sync::atomic::{AtomicBool, Ordering};
}
mod tcp;
mod tcp_lowering;
mod types;

pub use activation::activation_context_from_progress;
pub use tcp::{apply_tcp_capability_policy, primary_tcp_strategy_family, send_prepared_with_group};
pub use types::{OutboundSendError, OutboundSendOutcome, PcapHook};

pub const DESYNC_SEED_BASE: u32 = 7;
