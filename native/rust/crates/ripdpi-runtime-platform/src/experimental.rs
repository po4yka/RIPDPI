//! Public facade — tier-3 experimental raw-IP operations.
//!
//! Re-exports the `experimental_tier3` runtime-adaptation module (raw IP
//! packet emission, SYN-hide, ICMP-wrapped UDP). Every entry point dispatches
//! through the root helper when one is registered and otherwise falls back to
//! `ripdpi-privileged-ops`.

pub use super::experimental_tier3::{
    recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_raw_ip_packet, send_syn_hide_tcp, IcmpWrappedUdpRecvFilter,
    IcmpWrappedUdpRole, IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind, SynHideTcpSpec,
};
