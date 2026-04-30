//! Per-flow adaptive tuning of DPI evasion parameters.
//!
//! [`AdaptivePlannerResolver`] tracks a separate [`AdaptivePlannerState`] for
//! each unique (network-scope, group, flow-kind, target) tuple. On failure it
//! cycles through candidates in one adaptive dimension at a time (round-robin
//! across a shuffled dimension order), and on success it pins the current
//! candidate so it persists until the next failure.
//!
//! # 5-dimension cycling
//!
//! The five tunable dimensions are:
//!
//! 0. `split_offset_base` -- TCP split point strategy
//! 1. `tls_record_offset_base` -- TLS record split point
//! 2. `tlsrandrec_profile` -- TLS random record fragmentation profile
//! 3. `udp_burst_profile` -- UDP fake-burst intensity
//! 4. `quic_fake_profile` -- QUIC fake packet style
//!
//! On each failure, only **one** dimension advances its candidate index. The
//! dimension order is deterministically shuffled per flow key so different
//! flows explore different paths.
//!
//! # Interaction with the strategy evolver
//!
//! When the session-level strategy evolver
//! ([`crate::strategy_evolver::StrategyEvolver`]) is enabled, its hints take
//! priority over the per-flow hints produced here. In that mode the evolver
//! provides a single [`AdaptivePlannerHints`] for all flows and the per-flow
//! dimension cycling in this module is effectively bypassed for any dimension
//! the evolver sets. See `strategy_evolver` module docs for the full priority
//! chain.
//!
//! # Persistence
//!
//! [`AdaptivePlannerResolver`] persists its per-flow state to
//! `adaptive-tuning-v1.json`. When the runtime has a host-autolearn store path,
//! the adaptive store is written next to it; otherwise the current working
//! directory is used as a CLI/native fallback. Persisted state is versioned and
//! invalidated whenever the configured group layout changes.

mod candidates;
mod feedback;
mod key;
mod persistence;
mod resolver;
mod state;
mod types;

pub use resolver::AdaptivePlannerResolver;

#[cfg(test)]
mod tests;
