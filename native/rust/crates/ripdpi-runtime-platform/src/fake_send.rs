//! Runtime-adaptation — fake-packet TCP emission.
//!
//! Fake RST, fake TCP, flagged payloads, ordered segments, and sequence
//! overlap. Each `send_*` entry point dispatches through the root helper
//! (`root_helper_dispatch`) first and otherwise falls back to
//! `ripdpi-privileged-ops`; `raw_path_ids` reserves IPv4 identifications for
//! the local path. Non-Linux targets return `Unsupported`. Surfaced through
//! the `raw_packet` facade.

mod fake_rst;
mod fake_tcp;
mod flagged_payload;
mod ordered_segments;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod raw_path_ids;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod root_helper_dispatch;
mod seqovl;

pub use fake_rst::{send_fake_rst, send_fake_rst_reserved};
pub use fake_tcp::send_fake_tcp;
pub use flagged_payload::{send_flagged_tcp_payload, send_flagged_tcp_payload_reserved};
pub use ordered_segments::{send_ordered_tcp_segments, send_ordered_tcp_segments_reserved};
pub use seqovl::{send_seqovl_tcp, send_seqovl_tcp_reserved};
