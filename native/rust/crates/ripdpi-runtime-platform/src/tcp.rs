//! Public facade — TCP introspection and retransmit-capability detection.
//!
//! Aggregates the `tcp_info` OS-primitive adapter (`TCP_INFO`-derived activation
//! state, RTT, retransmission counts, segment hints) and the `retransmit`
//! OS-primitive adapter (fake-retransmit / seqovl support probes), plus the
//! privileged-ops value types they return.

pub use super::retransmit::{seqovl_supported, supports_fake_retransmit};
pub use super::tcp_info::{tcp_activation_state, tcp_round_trip_time_ms, tcp_segment_hint, tcp_total_retransmissions};
pub use ripdpi_privileged_ops::{TcpActivationState, TcpStageWait};
