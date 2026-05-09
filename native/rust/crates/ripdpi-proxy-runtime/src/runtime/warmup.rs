//! Post-start warmup probe that pre-populates the autolearn table.
//!
//! After the proxy listener starts, a background thread attempts TLS
//! connections to a small set of commonly-blocked domains. Each probe
//! walks the normal route-selection -> desync -> failure-classification
//! pipeline so that autolearn records are created before any user traffic
//! arrives. The warmup is non-blocking: it runs on a dedicated thread
//! and respects the runtime shutdown signal.

mod autolearn;
mod block_signal;
mod execution;
mod platform;
mod resolver;
mod scheduler;
mod target_catalog;

pub(super) use scheduler::spawn_warmup_thread;
