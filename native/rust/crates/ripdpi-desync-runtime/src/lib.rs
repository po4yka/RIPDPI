// Crate-local hardening for the unsafe-block annotation audit (H1).
// `ripdpi-desync-runtime` carries a small unsafe surface: a thread-local
// `*const dyn TcpDesyncPlatform` reborrow in the platform registry and the
// `setsockopt`/`getsockopt` `TCP_WINDOW_CLAMP` FFI wrappers in the
// Linux/Android test-support and test code. Per the `rust-unsafe` skill lint
// floor, every `unsafe` block here MUST carry an inline SAFETY comment.
// Re-enabling the workspace-deferred `undocumented_unsafe_blocks` lint
// crate-locally turns that into a build-time error while the rest of the
// workspace continues the gradual migration; the matching opt-in already
// lives in ripdpi-vless / ripdpi-io-uring / ripdpi-privileged-ops /
// ripdpi-proxy-runtime.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

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
pub use types::{
    OutboundSendError, OutboundSendOutcome, PcapHook, TcpExecutionDisposition, TcpExecutionReceipt, TcpFallbackReason,
    TcpOffsetMarkerBase, TcpStrategyFamily, TcpTerminalReason,
};

pub const DESYNC_SEED_BASE: u32 = 7;
