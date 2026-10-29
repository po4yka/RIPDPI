//! Public facade — socket-level option syscalls.
//!
//! Aggregates the `socket_options`, `bpf_timestamp`, and `original_destination`
//! OS-primitive adapter modules. Every entry point is a thin `#[cfg]`-split
//! wrapper over `ripdpi-privileged-ops` with a non-Linux fallback — no
//! root-helper dispatch.

pub use super::bpf_timestamp::attach_strip_timestamps;
pub use super::original_destination::original_dst;
pub use super::socket_options::{
    attach_drop_sack, bind_udp_low_port, detach_drop_sack, enable_tcp_fastopen_connect, set_rcvbuf, set_tcp_md5sig,
    set_tcp_window_clamp, wait_tcp_stage,
};
