mod capabilities;
mod fd;
mod fragmentation;
mod raw_packet;
mod socket_options;
mod tcp_info;
mod tcp_repair;
mod ttl;
mod types;

pub mod experimental_tier3;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod linux;

pub use capabilities::{detect_default_ttl, probe_ip_fragmentation_capabilities};
pub use experimental_tier3::{
    recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole,
    IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind, SynHideTcpSpec,
};
pub use fd::protect_socket;
pub use fragmentation::{send_ip_fragmented_tcp, send_ip_fragmented_udp};
pub use raw_packet::{
    send_fake_rst, send_flagged_tcp_payload, send_multi_disorder_tcp, send_ordered_tcp_segments, send_seqovl_tcp,
};
pub use socket_options::{
    attach_drop_sack, attach_strip_timestamps, bind_udp_low_port, detach_drop_sack, enable_tcp_fastopen_connect,
    original_dst, set_rcvbuf, set_tcp_md5sig, set_tcp_window_clamp, wait_tcp_stage,
};
pub use tcp_info::{tcp_activation_state, tcp_round_trip_time_ms, tcp_segment_hint, tcp_total_retransmissions};
pub use tcp_repair::swap_replacement_fd;
pub use ttl::{enable_recv_ttl, read_chunk_with_ttl, try_set_stream_ttl_with_outcome};
pub use types::{
    FakeTcpOptions, IpFragmentationCapabilities, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides,
    TcpPayloadSegment, TcpStageWait,
};

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn unsupported() -> std::io::Result<()> {
    Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
