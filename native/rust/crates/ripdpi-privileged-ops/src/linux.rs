//! Linux/Android platform socket operations.
//!
//! This module intentionally uses raw `libc::setsockopt`/`getsockopt` for
//! kernel-specific options not available in `socket2` (as of 0.5):
//! TCP_INFO, TCP_MD5SIG, TCP_FASTOPEN_CONNECT, SO_ATTACH_FILTER,
//! SO_ORIGINAL_DST, IP_RECVTTL, and `recvmsg` with CMSG ancillary data.
//!
//! Standard socket options use `socket2::SockRef` (see [`set_stream_ttl`]).
//!
//! # TTL write capability
//!
//! [`try_set_stream_ttl_with_outcome`] is the preferred entry point for new
//! code that needs to set a per-socket TTL. It returns a typed
//! [`CapabilityOutcome`] rather than an `io::Result`, making the
//! unavailable / permission-denied cases explicit at the call site.
//! The lower-level [`set_stream_ttl`] helper (returning `io::Result`) is kept
//! for internal use by the restore path where fire-and-forget semantics are
//! acceptable.

mod bpf;
mod experimental_tier3;
mod fd;
mod fragmentation;
mod mmap_region;
mod raw_packet;
mod socket_options;
mod tcp_info;
mod tcp_repair;

pub use bpf::{attach_drop_sack, attach_strip_timestamps, detach_drop_sack};
pub(crate) use experimental_tier3::send_ip_packet;
pub use experimental_tier3::{recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp};
pub(crate) use fd::{close_owned_fd, dup2_fd};
pub use fd::{original_dst, protect_socket};
pub use fragmentation::{
    probe_ip_fragmentation_capabilities, send_ip_fragmented_tcp, send_ip_fragmented_udp, send_multi_disorder_tcp,
};
pub use raw_packet::{
    send_fake_rst, send_fake_tcp, send_flagged_tcp_payload, send_ordered_tcp_segments, send_seqovl_tcp,
};
pub use socket_options::{
    bind_udp_low_port, enable_recv_ttl, enable_tcp_fastopen_connect, read_chunk_with_ttl, set_rcvbuf, set_tcp_md5sig,
    set_tcp_window_clamp, try_set_stream_ttl_with_outcome,
};
pub use tcp_info::{
    tcp_activation_state, tcp_round_trip_time_ms, tcp_segment_hint, tcp_total_retransmissions, wait_tcp_stage,
};

#[cfg(test)]
mod tests;
