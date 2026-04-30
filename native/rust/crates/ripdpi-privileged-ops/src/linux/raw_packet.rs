//! Linux raw-packet sending facade for crafted TCP and IP-fragment packets.
//!
//! Submodules isolate packet construction from raw-socket syscalls and TCP_REPAIR
//! state capture.

mod fake_tcp;
mod packet_builder;
mod raw_socket;
mod tcp_payload;

pub use fake_tcp::send_fake_tcp;
pub(crate) use packet_builder::{
    build_error_to_io, build_tcp_segment_packet, fragment_identification, resolve_raw_ttl,
};
pub(crate) use raw_socket::{probe_raw_socket, send_raw_fragments, send_raw_packets_with_delay};
pub use tcp_payload::{send_fake_rst, send_flagged_tcp_payload, send_ordered_tcp_segments, send_seqovl_tcp};

#[cfg(test)]
pub(super) use fake_tcp::mutate_fake_timestamp;
