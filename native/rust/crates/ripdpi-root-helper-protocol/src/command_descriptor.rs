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
    CMD_PROBE_CAPABILITIES, CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
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
        command: CMD_PROTOCOL_PREFLIGHT,
        params_type: None,
        requires_inbound_fd: false,
        may_return_outbound_fd: false,
        note: "Pure protocol-version handshake; performs no capability probe or privileged operation.",
    },
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

/// Typed failure modes for [`validate_request`].
///
/// Carried as the `Err` arm so callers (the client and the helper dispatch)
/// can route a descriptor violation to a uniform error response — no string
/// matching, no per-call-site re-implementation. `Display` produces a stable,
/// human-readable form that already mentions the command name and the rule
/// that fired; the existing `fd_requiring_commands_reject_a_missing_inbound_fd`
/// test only matches the substring `"fd"`, so the wording stays compatible
/// with the pre-existing per-handler messages.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DescriptorValidationError {
    /// The `command` field is not a known `CMD_*` constant.
    UnknownCommand(String),
    /// The descriptor's `requires_inbound_fd == true`, but the request carried
    /// no fd.
    MissingFd { command: &'static str },
    /// The descriptor's `requires_inbound_fd == false`, but the caller passed
    /// an fd anyway. The helper REJECTS the request rather than silently
    /// leaking the descriptor; the previous code closed the fd in `main.rs`
    /// only on session-nonce rejection, so an extra fd for a non-fd command
    /// would leak.
    UnexpectedFd { command: &'static str },
    /// The descriptor declares `params_type: Some(_)`, but the request's
    /// `params` field was `null` / missing.
    MissingParams { command: &'static str },
}

impl core::fmt::Display for DescriptorValidationError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::UnknownCommand(command) => write!(f, "unknown command: {command}"),
            Self::MissingFd { command } => write!(f, "{command} requires a stream fd"),
            Self::UnexpectedFd { command } => {
                write!(f, "{command} does not accept a stream fd")
            }
            Self::MissingParams { command } => write!(f, "{command} requires a params object"),
        }
    }
}

impl core::error::Error for DescriptorValidationError {}

/// Validate one [`HelperRequest`]-shaped tuple against its
/// [`CommandDescriptor`].
///
/// Inputs:
/// - `command` — the request's `command` wire string;
/// - `has_inbound_fd` — `true` when the request arrived with a `SCM_RIGHTS`
///   fd (the on-wire reality, not the descriptor's expectation);
/// - `params_present` — `true` when the request's `params` field is anything
///   other than JSON `null` (the on-wire reality). Empty `{}` counts as
///   present.
///
/// Returns the matching `CommandDescriptor` on success so callers can keep
/// the static metadata around without a second lookup. The validation rules
/// are intentionally narrow: command known, inbound-fd presence matches
/// `requires_inbound_fd` (in both directions), and the descriptor's
/// `params_type.is_some()` implies `params_present`. The reverse direction —
/// rejecting unexpected params for a no-params descriptor — is intentionally
/// NOT enforced: handlers for no-params commands (probe_capabilities,
/// shutdown) ignore the field, and several internal call sites pass an empty
/// object instead of `Null`.
///
/// Param shape / contents are NOT validated here — that's the per-command
/// `serde_json::from_value` decode step.
pub fn validate_request(
    command: &str,
    has_inbound_fd: bool,
    params_present: bool,
) -> Result<&'static CommandDescriptor, DescriptorValidationError> {
    let descriptor =
        command_descriptor(command).ok_or_else(|| DescriptorValidationError::UnknownCommand(command.to_owned()))?;
    match (descriptor.requires_inbound_fd, has_inbound_fd) {
        (true, false) => return Err(DescriptorValidationError::MissingFd { command: descriptor.command }),
        (false, true) => return Err(DescriptorValidationError::UnexpectedFd { command: descriptor.command }),
        _ => {}
    }
    if descriptor.params_type.is_some() && !params_present {
        return Err(DescriptorValidationError::MissingParams { command: descriptor.command });
    }
    Ok(descriptor)
}

#[cfg(test)]
mod tests {
    use super::{COMMAND_DESCRIPTORS, DescriptorValidationError, command_descriptor, validate_request};
    use crate::commands::{
        CMD_PROBE_CAPABILITIES, CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST,
        CMD_SEND_FAKE_TCP, CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP,
        CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET,
        CMD_SEND_SEQOVL_TCP, CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
    };

    /// Every `CMD_*` wire-string constant — the independent oracle for the
    /// descriptor-coverage tests. A new command must be added here too.
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
    fn validate_request_accepts_well_formed_requests() {
        for descriptor in COMMAND_DESCRIPTORS {
            let result =
                validate_request(descriptor.command, descriptor.requires_inbound_fd, descriptor.params_type.is_some());
            assert_eq!(result, Ok(descriptor), "well-formed `{}` must validate", descriptor.command);
        }
    }

    #[test]
    fn validate_request_rejects_unknown_commands() {
        let result = validate_request("totally_unknown_command_v999", false, false);
        assert_eq!(result, Err(DescriptorValidationError::UnknownCommand("totally_unknown_command_v999".to_owned())));
    }

    #[test]
    fn validate_request_rejects_missing_inbound_fd_for_fd_carrying_commands() {
        for command in FD_CARRYING_COMMANDS {
            let descriptor = command_descriptor(command).expect("descriptor");
            let result = validate_request(command, false, descriptor.params_type.is_some());
            assert_eq!(result, Err(DescriptorValidationError::MissingFd { command: descriptor.command }));
            // The Display impl must mention "fd" and the command name.
            let rendered = result.unwrap_err().to_string();
            assert!(rendered.contains("fd"), "MissingFd Display must mention fd: {rendered:?}");
            assert!(rendered.contains(command), "MissingFd Display must mention command: {rendered:?}");
        }
    }

    #[test]
    fn validate_request_rejects_extra_inbound_fd_for_non_fd_commands() {
        for descriptor in COMMAND_DESCRIPTORS {
            if descriptor.requires_inbound_fd {
                continue;
            }
            let result = validate_request(descriptor.command, true, descriptor.params_type.is_some());
            assert_eq!(result, Err(DescriptorValidationError::UnexpectedFd { command: descriptor.command }));
        }
    }

    #[test]
    fn validate_request_rejects_missing_params_for_params_required_commands() {
        for descriptor in COMMAND_DESCRIPTORS {
            if descriptor.params_type.is_none() {
                continue;
            }
            let result = validate_request(descriptor.command, descriptor.requires_inbound_fd, false);
            assert_eq!(result, Err(DescriptorValidationError::MissingParams { command: descriptor.command }));
        }
    }

    #[test]
    fn validate_request_tolerates_extra_params_for_no_params_commands() {
        // Handlers for no-params commands ignore the field; the validator
        // matches that lenience so an empty `{}` from a legacy client is not
        // rejected before reaching the handler.
        for descriptor in COMMAND_DESCRIPTORS {
            if descriptor.params_type.is_some() {
                continue;
            }
            let result = validate_request(descriptor.command, descriptor.requires_inbound_fd, true);
            assert_eq!(result, Ok(descriptor));
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
