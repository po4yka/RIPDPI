//! Runtime-adaptation — IP-fragmentation packet emission.
//!
//! IP-fragmented TCP/UDP, multi-disorder TCP, and the fragmentation-capability
//! probe. Each entry point dispatches through the root helper first and
//! otherwise falls back to `ripdpi-privileged-ops`; `replacement_fd` handles
//! descriptor swapping for the local path. Non-Linux targets return a default
//! / `Unsupported`. Surfaced through the `raw_packet` facade.

mod capabilities;
mod replacement_fd;
mod tcp;
mod udp;

pub use capabilities::probe_ip_fragmentation_capabilities;
pub use tcp::{
    send_ip_fragmented_tcp, send_ip_fragmented_tcp_reserved, send_multi_disorder_tcp, send_multi_disorder_tcp_reserved,
};
pub use udp::{send_ip_fragmented_udp, send_ip_fragmented_udp_reserved};
