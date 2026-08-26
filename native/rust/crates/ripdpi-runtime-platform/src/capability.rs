//! Public facade — runtime capability detection plus process/thread helpers.
//!
//! Aggregates the `capabilities` OS-primitive adapter (TTL capability probes)
//! and the `process` OS-primitive adapter (parallelism detection, shutdown
//! signal handlers). The two are surfaced together for historical reasons;
//! see the follow-up note in this crate's `README.md`.

pub use super::capabilities::{
    CapabilityOutcome, CapabilityUnavailable, RuntimeCapability, detect_default_ttl, try_set_stream_ttl_with_outcome,
};
pub use super::process::{
    daemonize, detected_parallelism, install_shutdown_signal_handlers, request_shutdown, reset_shutdown_request,
    shutdown_requested,
};
