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
/// outcomes lives in `ripdpi-runtime-platform`, where those capability types
/// are adapted for the platform client.
pub const CMD_PROBE_CAPABILITIES: &str = "v3/probe_capabilities";

/// Pure wire-version preflight used before any command with side effects.
///
/// The helper performs no capability probe, socket operation, or fd exchange;
/// the response version fields are the entire contract.
pub const CMD_PROTOCOL_PREFLIGHT: &str = "v3/protocol_preflight";

/// Emit a fake TCP segment (TTL-limited / decoy) ahead of the real payload.
/// The client passes the live TCP socket fd via `SCM_RIGHTS`; the helper may
/// return a replacement fd the client swaps in with `dup2`.
pub const CMD_SEND_FAKE_TCP: &str = "v3/send_fake_tcp";

/// Emit a fake TCP RST segment on the client's TCP socket fd, which is passed
/// via `SCM_RIGHTS`.
pub const CMD_SEND_FAKE_RST: &str = "v3/send_fake_rst";

/// Send a TCP payload with overridden TCP flags on the passed socket fd; the
/// helper may return a replacement fd.
pub const CMD_SEND_FLAGGED_TCP_PAYLOAD: &str = "v3/send_flagged_tcp_payload";

/// Send a sequence-overlapped TCP segment (a fake prefix overlapping the real
/// chunk) on the passed socket fd; the helper may return a replacement fd.
pub const CMD_SEND_SEQOVL_TCP: &str = "v3/send_seqovl_tcp";

/// Send several TCP segments out of order on the passed socket fd; the helper
/// may return a replacement fd.
pub const CMD_SEND_MULTI_DISORDER_TCP: &str = "v3/send_multi_disorder_tcp";

/// Send explicitly ordered TCP segments on the passed socket fd; the helper
/// may return a replacement fd.
pub const CMD_SEND_ORDERED_TCP_SEGMENTS: &str = "v3/send_ordered_tcp_segments";

/// Send an IP-fragmented TCP packet on the passed socket fd; the helper may
/// return a replacement fd.
pub const CMD_SEND_IP_FRAGMENTED_TCP: &str = "v3/send_ip_fragmented_tcp";

/// Send an IP-fragmented UDP datagram. The client passes the live UDP socket
/// fd via `SCM_RIGHTS`.
pub const CMD_SEND_IP_FRAGMENTED_UDP: &str = "v3/send_ip_fragmented_udp";

/// Experimental (`lab_diagnostics_only`): emit a SYN-hide TCP probe to the
/// target. No fd is passed; the helper opens its own raw socket.
pub const CMD_SEND_SYN_HIDE_TCP: &str = "v3/send_syn_hide_tcp";

/// Experimental (`lab_diagnostics_only`): send a UDP datagram wrapped in an
/// ICMP packet. No fd is passed.
pub const CMD_SEND_ICMP_WRAPPED_UDP: &str = "v3/send_icmp_wrapped_udp";

/// Experimental (`lab_diagnostics_only`): receive an ICMP-wrapped UDP
/// datagram. The parsed message is returned in the response `data`. No fd is
/// passed.
pub const CMD_RECV_ICMP_WRAPPED_UDP: &str = "v3/recv_icmp_wrapped_udp";

/// Experimental (`lab_diagnostics_only`): send a caller-supplied raw IP packet
/// to a target address. No fd is passed.
pub const CMD_SEND_RAW_IP_PACKET: &str = "v3/send_raw_ip_packet";

/// Ask the helper to finish the in-flight request and exit cleanly. No fd is
/// passed; issued by `RootHelperManager` during shutdown.
pub const CMD_SHUTDOWN: &str = "v3/shutdown";

#[cfg(test)]
mod tests {
    use super::{
        CMD_PROBE_CAPABILITIES, CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST,
        CMD_SEND_FAKE_TCP, CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP,
        CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET,
        CMD_SEND_SEQOVL_TCP, CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
    };

    /// Every `CMD_*` wire string. A change to this set is a breaking protocol
    /// change — see `docs/architecture/ROOT_HELPER_CONTRACT.md`.
    const ALL_COMMANDS: [&str; 15] = [
        CMD_PROTOCOL_PREFLIGHT,
        CMD_PROBE_CAPABILITIES,
        CMD_SEND_FAKE_TCP,
        CMD_SEND_FAKE_RST,
        CMD_SEND_FLAGGED_TCP_PAYLOAD,
        CMD_SEND_SEQOVL_TCP,
        CMD_SEND_MULTI_DISORDER_TCP,
        CMD_SEND_ORDERED_TCP_SEGMENTS,
        CMD_SEND_IP_FRAGMENTED_TCP,
        CMD_SEND_IP_FRAGMENTED_UDP,
        CMD_SEND_SYN_HIDE_TCP,
        CMD_SEND_ICMP_WRAPPED_UDP,
        CMD_RECV_ICMP_WRAPPED_UDP,
        CMD_SEND_RAW_IP_PACKET,
        CMD_SHUTDOWN,
    ];

    #[test]
    fn command_wire_strings_are_frozen() {
        // These literals are the cross-process wire contract; a diff here must
        // be a deliberate, reviewed protocol change, never an incidental edit.
        assert_eq!(CMD_PROBE_CAPABILITIES, "v3/probe_capabilities");
        assert_eq!(CMD_PROTOCOL_PREFLIGHT, "v3/protocol_preflight");
        assert_eq!(CMD_SEND_FAKE_TCP, "v3/send_fake_tcp");
        assert_eq!(CMD_SEND_FAKE_RST, "v3/send_fake_rst");
        assert_eq!(CMD_SEND_FLAGGED_TCP_PAYLOAD, "v3/send_flagged_tcp_payload");
        assert_eq!(CMD_SEND_SEQOVL_TCP, "v3/send_seqovl_tcp");
        assert_eq!(CMD_SEND_MULTI_DISORDER_TCP, "v3/send_multi_disorder_tcp");
        assert_eq!(CMD_SEND_ORDERED_TCP_SEGMENTS, "v3/send_ordered_tcp_segments");
        assert_eq!(CMD_SEND_IP_FRAGMENTED_TCP, "v3/send_ip_fragmented_tcp");
        assert_eq!(CMD_SEND_IP_FRAGMENTED_UDP, "v3/send_ip_fragmented_udp");
        assert_eq!(CMD_SEND_SYN_HIDE_TCP, "v3/send_syn_hide_tcp");
        assert_eq!(CMD_SEND_ICMP_WRAPPED_UDP, "v3/send_icmp_wrapped_udp");
        assert_eq!(CMD_RECV_ICMP_WRAPPED_UDP, "v3/recv_icmp_wrapped_udp");
        assert_eq!(CMD_SEND_RAW_IP_PACKET, "v3/send_raw_ip_packet");
        assert_eq!(CMD_SHUTDOWN, "v3/shutdown");
    }

    #[test]
    fn command_wire_strings_are_unique() {
        let unique: std::collections::BTreeSet<&str> = ALL_COMMANDS.iter().copied().collect();
        assert_eq!(unique.len(), ALL_COMMANDS.len(), "every CMD_* wire string must be distinct");
    }

    #[test]
    fn command_wire_strings_are_versioned_lowercase_snake_case() {
        for command in ALL_COMMANDS {
            let operation = command.strip_prefix("v3/").expect("command must be namespaced to protocol v3");
            assert!(!operation.is_empty(), "command operation must not be empty");
            assert!(
                operation.bytes().all(|byte| byte.is_ascii_lowercase() || byte == b'_'),
                "command operation {operation} must be lowercase snake_case",
            );
        }
    }
}
