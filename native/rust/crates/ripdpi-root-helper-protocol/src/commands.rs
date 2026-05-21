//! Root-helper IPC command identifiers.
//!
//! Each `CMD_*` constant is the `command` field of a [`HelperRequest`] and a
//! **frozen wire contract** shared by the privileged `ripdpi-root-helper`
//! binary and the `ripdpi-runtime-platform` client — renaming or repurposing
//! one is a breaking protocol change; add, never rename. Every command is
//! rejected unless the request carries the matching session nonce.
//!
//! See `docs/architecture/ROOT_HELPER_CONTRACT.md` for the full command
//! table, parameter shapes, and `SCM_RIGHTS` fd-passing rules.
//!
//! [`HelperRequest`]: crate::HelperRequest

/// Request the helper to probe what privileged capabilities are available.
///
/// Response `data` shape (all fields are JSON booleans):
/// ```json
/// { "raw_ipv4": true, "raw_ipv6": false, "tcp_repair": true }
/// ```
/// - `raw_ipv4`  - helper can open `AF_INET  / SOCK_RAW` sockets.
/// - `raw_ipv6`  - helper can open `AF_INET6 / SOCK_RAW` sockets.
/// - `tcp_repair` - helper can set `TCP_REPAIR` socket option.
///
/// Runtime-side conversion from this JSON shape to typed runtime capability
/// outcomes lives in `ripdpi-runtime`, where those capability types are defined.
pub const CMD_PROBE_CAPABILITIES: &str = "probe_capabilities";

/// Emit a fake TCP segment (TTL-limited / decoy) ahead of the real payload.
/// The client passes the live TCP socket fd via `SCM_RIGHTS`; the helper may
/// return a replacement fd the client swaps in with `dup2`.
pub const CMD_SEND_FAKE_TCP: &str = "send_fake_tcp";

/// Emit a fake TCP RST segment on the client's TCP socket fd, which is passed
/// via `SCM_RIGHTS`.
pub const CMD_SEND_FAKE_RST: &str = "send_fake_rst";

/// Send a TCP payload with overridden TCP flags on the passed socket fd; the
/// helper may return a replacement fd.
pub const CMD_SEND_FLAGGED_TCP_PAYLOAD: &str = "send_flagged_tcp_payload";

/// Send a sequence-overlapped TCP segment (a fake prefix overlapping the real
/// chunk) on the passed socket fd; the helper may return a replacement fd.
pub const CMD_SEND_SEQOVL_TCP: &str = "send_seqovl_tcp";

/// Send several TCP segments out of order on the passed socket fd; the helper
/// may return a replacement fd.
pub const CMD_SEND_MULTI_DISORDER_TCP: &str = "send_multi_disorder_tcp";

/// Send explicitly ordered TCP segments on the passed socket fd; the helper
/// may return a replacement fd.
pub const CMD_SEND_ORDERED_TCP_SEGMENTS: &str = "send_ordered_tcp_segments";

/// Send an IP-fragmented TCP packet on the passed socket fd; the helper may
/// return a replacement fd.
pub const CMD_SEND_IP_FRAGMENTED_TCP: &str = "send_ip_fragmented_tcp";

/// Send an IP-fragmented UDP datagram. The client passes the live UDP socket
/// fd via `SCM_RIGHTS`.
pub const CMD_SEND_IP_FRAGMENTED_UDP: &str = "send_ip_fragmented_udp";

/// Experimental (`lab_diagnostics_only`): emit a SYN-hide TCP probe to the
/// target. No fd is passed; the helper opens its own raw socket.
pub const CMD_SEND_SYN_HIDE_TCP: &str = "send_syn_hide_tcp";

/// Experimental (`lab_diagnostics_only`): send a UDP datagram wrapped in an
/// ICMP packet. No fd is passed.
pub const CMD_SEND_ICMP_WRAPPED_UDP: &str = "send_icmp_wrapped_udp";

/// Experimental (`lab_diagnostics_only`): receive an ICMP-wrapped UDP
/// datagram. The parsed message is returned in the response `data`. No fd is
/// passed.
pub const CMD_RECV_ICMP_WRAPPED_UDP: &str = "recv_icmp_wrapped_udp";

/// Experimental (`lab_diagnostics_only`): send a caller-supplied raw IP packet
/// to a target address. No fd is passed.
pub const CMD_SEND_RAW_IP_PACKET: &str = "send_raw_ip_packet";

/// Ask the helper to finish the in-flight request and exit cleanly. No fd is
/// passed; issued by `RootHelperManager` during shutdown.
pub const CMD_SHUTDOWN: &str = "shutdown";
