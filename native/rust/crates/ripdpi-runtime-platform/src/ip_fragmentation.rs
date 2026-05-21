//! Runtime-adaptation — IP-fragmentation packet emission.
//!
//! IP-fragmented TCP/UDP, multi-disorder TCP, and the fragmentation-capability
//! probe. Each entry point dispatches through the root helper first and
//! otherwise falls back to `ripdpi-privileged-ops`. On the root-helper branch
//! `replacement_fd` installs any replacement descriptor the helper returns.
//! Non-Linux targets return a default / `Unsupported`. Surfaced through the
//! `raw_packet` facade.
//!
//! TCP emission is split per desync tactic: `tcp_fragment` (IP-fragmented TCP)
//! and `multi_disorder` (out-of-order TCP segments). Each owns its single
//! `unsafe` fd-swap call site — see those modules' headers.

mod capabilities;
mod multi_disorder;
mod replacement_fd;
mod tcp_fragment;
mod udp;

pub use capabilities::probe_ip_fragmentation_capabilities;
pub use multi_disorder::{send_multi_disorder_tcp, send_multi_disorder_tcp_reserved};
pub use tcp_fragment::{send_ip_fragmented_tcp, send_ip_fragmented_tcp_reserved};
pub use udp::{send_ip_fragmented_udp, send_ip_fragmented_udp_reserved};
