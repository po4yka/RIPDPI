//! Public facade — raw TCP/UDP packet emission.
//!
//! Aggregates the `fake_send` and `ip_fragmentation` runtime-adaptation
//! modules (fake RST/TCP, flagged payloads, ordered segments, seqovl, IP
//! fragmentation, multi-disorder) and the privileged-ops value types they
//! take. Each `send_*` entry point dispatches through the root helper when
//! one is registered and otherwise falls back to `ripdpi-privileged-ops`.

pub use super::fake_send::{
    send_fake_rst, send_fake_rst_reserved, send_fake_tcp, send_flagged_tcp_payload, send_flagged_tcp_payload_reserved,
    send_ordered_tcp_segments, send_ordered_tcp_segments_reserved, send_seqovl_tcp, send_seqovl_tcp_reserved,
};
pub use super::ip_fragmentation::{
    probe_ip_fragmentation_capabilities, send_ip_fragmented_tcp, send_ip_fragmented_tcp_reserved,
    send_ip_fragmented_udp, send_ip_fragmented_udp_reserved, send_multi_disorder_tcp, send_multi_disorder_tcp_reserved,
};
pub use ripdpi_privileged_ops::{
    FakeTcpOptions, IpFragmentationCapabilities, OrderedTcpSegment, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait,
};
