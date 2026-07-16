//! Privileged Linux TUN soak entrypoint.
//!
//! The implementation remains shared with the E2E harness, while the dedicated
//! Cargo target and `soak_` filter keep the scheduled endurance lane isolated
//! from the shorter data-plane checks.

#![cfg(target_os = "linux")]

#[path = "linux_tun_e2e.rs"]
mod harness;
