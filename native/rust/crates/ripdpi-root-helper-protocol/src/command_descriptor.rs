//! Static metadata inventory for the root-helper IPC command set.
//!
//! Each [`CMD_*`](crate::commands) constant is a frozen wire string; on its
//! own it carries no machine-readable description of what the command expects
//! or returns. [`COMMAND_DESCRIPTORS`] pairs every command with a
//! [`CommandDescriptor`] recording its params payload type, whether it
//! exchanges a socket fd over `SCM_RIGHTS`, and a short capability note.
//!
//! This table is **metadata, not a dispatcher**. The privileged
//! `ripdpi-root-helper` binary still routes commands through its own
//! `match`; the descriptor table exists so drift tests can pin the command
//! set, the `SCM_RIGHTS` fd-passing contract, and helper handler coverage.
//! See `docs/architecture/ROOT_HELPER_CONTRACT.md`.

use crate::commands::{
    CMD_PROBE_CAPABILITIES, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
    CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP,
    CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET, CMD_SEND_SEQOVL_TCP,
    CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
};

/// Static metadata for one root-helper IPC command.
///
/// Purely descriptive: it records the wire string, the params payload type,
/// the `SCM_RIGHTS` fd-passing contract, and a capability note. It drives no
/// dispatch — the helper binary owns command routing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CommandDescriptor {
    /// The `CMD_*` wire string — a frozen cross-process contract.
    pub command: &'static str,
    /// Rust type name of the command's `params` JSON payload, or `None` when
    /// the command takes no params (an empty / `null` `params` field).
    pub params_type: Option<&'static str>,
    /// The client must pass a live socket fd alongside the request via
    /// `SCM_RIGHTS`; the helper rejects the command when the fd is absent.
    pub requires_inbound_fd: bool,
    /// The helper may return a (possibly replacement) socket fd via
    /// `SCM_RIGHTS` in the response. Only ever set together with
    /// [`Self::requires_inbound_fd`].
    pub may_return_outbound_fd: bool,
    /// Short capability / fallback note for inventory and audit surfaces.
    pub note: &'static str,
}

impl CommandDescriptor {
    /// Whether this command exchanges a socket fd over `SCM_RIGHTS` in either
    /// direction. Commands that do not are plain JSON request/response.
    #[must_use]
    pub const fn is_fd_carrying(&self) -> bool {
        self.requires_inbound_fd || self.may_return_outbound_fd
    }
}

/// Static descriptor table — one row per `CMD_*` constant.
///
/// Drift tests pin this table to the `CMD_*` set and to the helper's dispatch
/// handlers; see the tests in this module and in `ripdpi-root-helper`.
pub static COMMAND_DESCRIPTORS: &[CommandDescriptor] = &[
    CommandDescriptor {
        command: CMD_PROBE_CAPABILITIES,
        params_type: None,
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Probes raw-socket and TCP_REPAIR availability; sends no packet.",
    },
    CommandDescriptor {
        command: CMD_SEND_FAKE_TCP,
        params_type: Some("FakeTcpParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: true,
        note: "Raw-socket fake TCP segment ahead of the caller's payload.",
    },
    CommandDescriptor {
        command: CMD_SEND_FAKE_RST,
        params_type: Some("FakeRstParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: false,
        note: "Raw-socket fake TCP RST on the caller's connected socket.",
    },
    CommandDescriptor {
        command: CMD_SEND_FLAGGED_TCP_PAYLOAD,
        params_type: Some("FlaggedTcpPayloadParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: true,
        note: "Raw-socket TCP payload with overridden TCP flags.",
    },
    CommandDescriptor {
        command: CMD_SEND_SEQOVL_TCP,
        params_type: Some("SeqOvlParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: true,
        note: "Raw-socket sequence-overlapped TCP segment.",
    },
    CommandDescriptor {
        command: CMD_SEND_MULTI_DISORDER_TCP,
        params_type: Some("MultiDisorderParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: true,
        note: "Raw-socket out-of-order TCP segments.",
    },
    CommandDescriptor {
        command: CMD_SEND_ORDERED_TCP_SEGMENTS,
        params_type: Some("OrderedTcpSegmentsParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: true,
        note: "Raw-socket explicitly ordered TCP segments.",
    },
    CommandDescriptor {
        command: CMD_SEND_IP_FRAGMENTED_TCP,
        params_type: Some("IpFragTcpParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: true,
        note: "Raw-socket IP-fragmented TCP packet.",
    },
    CommandDescriptor {
        command: CMD_SEND_IP_FRAGMENTED_UDP,
        params_type: Some("IpFragUdpParams"),
        requires_inbound_fd: true,
        may_return_outbound_fd: false,
        note: "Raw-socket IP-fragmented UDP datagram.",
    },
    CommandDescriptor {
        command: CMD_SEND_SYN_HIDE_TCP,
        params_type: Some("SynHideTcpSpec"),
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Experimental (lab_diagnostics_only); helper opens its own raw socket.",
    },
    CommandDescriptor {
        command: CMD_SEND_ICMP_WRAPPED_UDP,
        params_type: Some("IcmpWrappedUdpSpec"),
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Experimental (lab_diagnostics_only); ICMP-wrapped UDP send.",
    },
    CommandDescriptor {
        command: CMD_RECV_ICMP_WRAPPED_UDP,
        params_type: Some("IcmpWrappedUdpRecvFilter"),
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Experimental (lab_diagnostics_only); receives an ICMP-wrapped UDP datagram.",
    },
    CommandDescriptor {
        command: CMD_SEND_RAW_IP_PACKET,
        params_type: Some("RawIpPacketParams"),
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Experimental (lab_diagnostics_only); sends a caller-supplied raw IP packet.",
    },
    CommandDescriptor {
        command: CMD_SHUTDOWN,
        params_type: None,
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Control command; asks the helper to exit cleanly. No privileged operation.",
    },
];

/// Look up the [`CommandDescriptor`] for a command wire string.
///
/// Returns `None` for any string that is not a known `CMD_*` constant.
#[must_use]
pub fn command_descriptor(command: &str) -> Option<&'static CommandDescriptor> {
    COMMAND_DESCRIPTORS.iter().find(|descriptor| descriptor.command == command)
}

#[cfg(test)]
mod tests {
    use super::{command_descriptor, COMMAND_DESCRIPTORS};
    use crate::commands::{
        CMD_PROBE_CAPABILITIES, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
        CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP,
        CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET,
        CMD_SEND_SEQOVL_TCP, CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
    };

    /// Every `CMD_*` wire-string constant — the independent oracle for the
    /// descriptor-coverage tests. A new command must be added here too.
    const ALL_COMMANDS: [&str; 14] = [
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

    /// Commands that exchange a socket fd over `SCM_RIGHTS` (inbound, and for
    /// some also an outbound replacement). Independent oracle for the
    /// fd-metadata tests.
    const FD_CARRYING_COMMANDS: [&str; 8] = [
        CMD_SEND_FAKE_TCP,
        CMD_SEND_FAKE_RST,
        CMD_SEND_FLAGGED_TCP_PAYLOAD,
        CMD_SEND_SEQOVL_TCP,
        CMD_SEND_MULTI_DISORDER_TCP,
        CMD_SEND_ORDERED_TCP_SEGMENTS,
        CMD_SEND_IP_FRAGMENTED_TCP,
        CMD_SEND_IP_FRAGMENTED_UDP,
    ];

    #[test]
    fn every_command_constant_has_exactly_one_descriptor() {
        for command in ALL_COMMANDS {
            let matches = COMMAND_DESCRIPTORS.iter().filter(|descriptor| descriptor.command == command).count();
            assert_eq!(1, matches, "expected exactly one descriptor for `{command}`");
            assert!(command_descriptor(command).is_some(), "`{command}` must resolve to a descriptor");
        }
    }

    #[test]
    fn every_descriptor_command_is_a_known_constant() {
        for descriptor in COMMAND_DESCRIPTORS {
            assert!(
                ALL_COMMANDS.contains(&descriptor.command),
                "descriptor command `{}` is not a known CMD_* constant",
                descriptor.command,
            );
        }
        assert_eq!(COMMAND_DESCRIPTORS.len(), ALL_COMMANDS.len(), "descriptor count must match the CMD_* set");
        assert!(
            command_descriptor("totally_unknown_command_v999").is_none(),
            "unknown strings resolve to no descriptor"
        );
    }

    #[test]
    fn scm_rights_commands_are_marked_fd_carrying() {
        for command in FD_CARRYING_COMMANDS {
            let descriptor = command_descriptor(command).unwrap_or_else(|| panic!("missing descriptor for {command}"));
            assert!(descriptor.requires_inbound_fd, "{command} passes a socket fd via SCM_RIGHTS");
            assert!(descriptor.is_fd_carrying(), "{command} must be marked fd-carrying");
        }
    }

    #[test]
    fn non_scm_rights_commands_have_no_fd_metadata() {
        for descriptor in COMMAND_DESCRIPTORS {
            if FD_CARRYING_COMMANDS.contains(&descriptor.command) {
                continue;
            }
            assert!(
                !descriptor.requires_inbound_fd && !descriptor.may_return_outbound_fd,
                "{} does not use SCM_RIGHTS and must carry no fd metadata",
                descriptor.command,
            );
            assert!(!descriptor.is_fd_carrying(), "{} must not be marked fd-carrying", descriptor.command);
        }
    }

    #[test]
    fn descriptor_metadata_is_self_consistent() {
        let mut seen: Vec<&str> = Vec::new();
        for descriptor in COMMAND_DESCRIPTORS {
            assert!(!descriptor.command.is_empty(), "descriptor command must not be empty");
            assert!(!seen.contains(&descriptor.command), "duplicate descriptor for {}", descriptor.command);
            seen.push(descriptor.command);
            assert!(!descriptor.note.is_empty(), "{} descriptor needs a note", descriptor.command);
            if let Some(params_type) = descriptor.params_type {
                assert!(!params_type.is_empty(), "{} params type name must not be empty", descriptor.command);
            }
            // An outbound replacement fd is only ever returned for a command
            // that received an inbound fd to begin with.
            if descriptor.may_return_outbound_fd {
                assert!(
                    descriptor.requires_inbound_fd,
                    "{} returns an fd but requires none inbound",
                    descriptor.command,
                );
            }
        }
    }
}
