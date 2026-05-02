mod bpf_timestamp;
mod capabilities;
mod experimental_tier3;
mod fake_send;
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
mod io_uring;
mod ip_fragmentation;
mod ipv4_ids;
mod original_destination;
mod retransmit;
mod socket_options;
mod tcp_info;
mod ttl;
mod vpn_protect;

pub mod protect;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper_client;

#[cfg(test)]
mod tests;

pub use self::capabilities::{
    detect_default_ttl, try_set_stream_ttl_with_outcome, CapabilityOutcome, CapabilityUnavailable, RuntimeCapability,
};
pub use bpf_timestamp::attach_strip_timestamps;
// Tier-3 primitives are exported pub from the runtime lib so external crates
// (e.g. integration tests, the privileged-ops staging crate) can pin against
// the staging API surface; treat as `pub(crate)` semantically until wired
// through `DesyncMode` or UI. See
// docs/architecture/README.md#desync-and-relay-rules.
pub use experimental_tier3::{
    recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole,
    IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind, SynHideTcpSpec,
};
pub use fake_send::{
    send_fake_rst, send_fake_rst_reserved, send_fake_tcp, send_flagged_tcp_payload, send_flagged_tcp_payload_reserved,
    send_ordered_tcp_segments, send_ordered_tcp_segments_reserved, send_seqovl_tcp, send_seqovl_tcp_reserved,
};
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
pub use io_uring::io_uring_capabilities;
pub use ip_fragmentation::{
    probe_ip_fragmentation_capabilities, send_ip_fragmented_tcp, send_ip_fragmented_tcp_reserved,
    send_ip_fragmented_udp, send_ip_fragmented_udp_reserved, send_multi_disorder_tcp, send_multi_disorder_tcp_reserved,
};
pub use original_destination::original_dst;
pub use retransmit::{seqovl_supported, supports_fake_retransmit};
pub use ripdpi_privileged_ops::{
    FakeTcpOptions, IpFragmentationCapabilities, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides,
    TcpPayloadSegment, TcpStageWait,
};
pub use socket_options::{
    attach_drop_sack, bind_udp_low_port, detach_drop_sack, enable_tcp_fastopen_connect, set_rcvbuf, set_tcp_md5sig,
    set_tcp_window_clamp, wait_tcp_stage,
};
pub use tcp_info::{tcp_activation_state, tcp_round_trip_time_ms, tcp_segment_hint, tcp_total_retransmissions};
pub use ttl::{enable_recv_ttl, read_chunk_with_ttl};
pub use vpn_protect::protect_socket;
