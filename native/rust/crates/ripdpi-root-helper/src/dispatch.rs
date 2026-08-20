mod capabilities;
mod experimental;
mod fragmentation;
mod shutdown;
mod tcp_payload;

use std::os::fd::RawFd;

use ripdpi_root_helper_protocol as protocol;
use ripdpi_root_helper_protocol::{
    CMD_PROBE_CAPABILITIES, CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
    CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP,
    CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET, CMD_SEND_SEQOVL_TCP,
    CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN, DescriptorValidationError, HelperRequest, validate_request,
};

/// The wire strings the dispatch `match` below has an arm for, in match order.
///
/// Companion to `protocol::COMMAND_DESCRIPTORS` for the descriptor-coverage
/// drift test: every entry here must have a descriptor and vice versa. Update
/// this table whenever a dispatch arm is added or removed.
///
/// Test-only: the production dispatch path uses the match directly; this
/// table exists so a future refactor that adds or removes a dispatch arm
/// without updating the descriptor table fails the drift test.
#[cfg(test)]
pub(crate) const DISPATCH_COMMANDS: &[&str] = &[
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

pub(crate) struct DispatchOutcome {
    pub(crate) response: protocol::HelperResponse,
    pub(crate) reply_fd: Option<RawFd>,
    pub(crate) shutdown_requested: bool,
}

impl DispatchOutcome {
    pub(crate) fn command(response: protocol::HelperResponse, reply_fd: Option<RawFd>) -> Self {
        Self { response, reply_fd, shutdown_requested: false }
    }

    pub(crate) fn shutdown(response: protocol::HelperResponse) -> Self {
        Self { response, reply_fd: None, shutdown_requested: true }
    }
}

/// Dispatch a single request against the descriptor-validated command set.
///
/// Order of operations:
///
/// 1. The request protocol version is validated before descriptor lookup or
///    handler dispatch. A stale/unversioned client cannot trigger a privileged
///    side effect.
/// 2. `validate_request` checks the request against the static
///    `CommandDescriptor` table — unknown command, missing inbound fd, extra
///    inbound fd, missing params. On failure the response is the typed
///    error's `Display` form and no per-handler arm runs.
/// 2. On `UnexpectedFd`, `UnknownCommand`, or `MissingParams` failure we
///    `close_received_fd(received_fd)` so an inbound fd attached to a
///    rejected request never leaks across the uid-0 boundary — the
///    pre-validation helper used to silently leak in these paths.
/// 3. On success the per-command match arm dispatches as before. The
///    per-handler `require_fd` / `decode_params` checks remain in place as
///    defence-in-depth.
pub(crate) fn dispatch_command(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    if let Err(error) = request.validate_protocol_version() {
        close_received_fd(received_fd);
        return DispatchOutcome::command(protocol::HelperResponse::error(error.to_string()), None);
    }
    match validate_request(&request.command, received_fd.is_some(), !request.params.is_null()) {
        Ok(_) => {}
        Err(error) => {
            close_received_fd(received_fd);
            return DispatchOutcome::command(protocol::HelperResponse::error(error.to_string()), None);
        }
    }

    match request.command.as_str() {
        CMD_PROTOCOL_PREFLIGHT => {
            DispatchOutcome::command(protocol::HelperResponse::success(serde_json::Value::Null), None)
        }
        CMD_PROBE_CAPABILITIES => capabilities::dispatch_probe_capabilities(),
        CMD_SEND_FAKE_TCP => tcp_payload::dispatch_send_fake_tcp(request, received_fd),
        CMD_SEND_FAKE_RST => tcp_payload::dispatch_send_fake_rst(request, received_fd),
        CMD_SEND_FLAGGED_TCP_PAYLOAD => tcp_payload::dispatch_send_flagged_tcp_payload(request, received_fd),
        CMD_SEND_SEQOVL_TCP => tcp_payload::dispatch_send_seqovl_tcp(request, received_fd),
        CMD_SEND_MULTI_DISORDER_TCP => tcp_payload::dispatch_send_multi_disorder_tcp(request, received_fd),
        CMD_SEND_ORDERED_TCP_SEGMENTS => tcp_payload::dispatch_send_ordered_tcp_segments(request, received_fd),
        CMD_SEND_IP_FRAGMENTED_TCP => fragmentation::dispatch_send_ip_fragmented_tcp(request, received_fd),
        CMD_SEND_IP_FRAGMENTED_UDP => fragmentation::dispatch_send_ip_fragmented_udp(request, received_fd),
        CMD_SEND_SYN_HIDE_TCP => experimental::dispatch_send_syn_hide_tcp(request),
        CMD_SEND_ICMP_WRAPPED_UDP => experimental::dispatch_send_icmp_wrapped_udp(request),
        CMD_RECV_ICMP_WRAPPED_UDP => experimental::dispatch_recv_icmp_wrapped_udp(request),
        CMD_SEND_RAW_IP_PACKET => experimental::dispatch_send_raw_ip_packet(request),
        CMD_SHUTDOWN => shutdown::dispatch_shutdown(),
        // Unreachable: validate_request above already maps every wire string
        // in DISPATCH_COMMANDS to Ok and every other string to UnknownCommand
        // — kept defensively so a missing arm produces a clear runtime error
        // rather than a `match` non-exhaustiveness compile failure on a
        // string. The drift test `every_dispatch_arm_has_a_descriptor` keeps
        // DISPATCH_COMMANDS in sync.
        other => DispatchOutcome::command(
            protocol::HelperResponse::error(DescriptorValidationError::UnknownCommand(other.to_owned()).to_string()),
            None,
        ),
    }
}

/// Close the inbound `SCM_RIGHTS` fd attached to a request the dispatch path
/// refused to consume — descriptor-validation rejection paths and the
/// catch-all.
///
/// Mirrors the same helper in `main.rs`; duplicated rather than shared to
/// keep dispatch self-contained. The fd ownership was transferred to this
/// process at `recv_message`; if no handler wraps it via `FromRawFd`, it
/// leaks unless we close here.
fn close_received_fd(fd: Option<RawFd>) {
    if let Some(fd) = fd {
        // SAFETY: `fd` was transferred to this process via `SCM_RIGHTS` and no
        // handler has consumed it on this rejection path.
        unsafe {
            libc::close(fd);
        }
    }
}

fn decode_params<T>(request: &HelperRequest) -> Result<T, protocol::HelperResponse>
where
    T: serde::de::DeserializeOwned,
{
    serde_json::from_value(request.params.clone())
        .map_err(|error| protocol::HelperResponse::error(format!("invalid params: {error}")))
}

fn require_fd(received_fd: Option<RawFd>, error: &'static str) -> Result<RawFd, protocol::HelperResponse> {
    received_fd.ok_or_else(|| protocol::HelperResponse::error(error))
}

/// Test-only fd helpers shared by the per-module fd-leak regression tests.
///
/// The leak sites (descriptor rejection, `decode_params` failure, and the UDP
/// `target_addr` parse error) must close the inbound SCM_RIGHTS fd exactly
/// once. These helpers mint a real owned socket fd and assert it is `EBADF`
/// (closed) after dispatch, so a reintroduced leak fails the test.
#[cfg(test)]
pub(crate) mod test_support {
    use std::os::fd::RawFd;

    /// Mint a real, owned socket fd (one half of a UNIX socketpair) and leak
    /// ownership of the integer so a dispatch path can adopt and close it. The
    /// other half is closed immediately — only the returned raw fd matters.
    pub(crate) fn leak_owned_socket_fd() -> RawFd {
        let mut fds = [0 as RawFd; 2];
        // SAFETY: `fds` is a valid 2-element array; `socketpair` fills both
        // entries with fresh kernel descriptors on success.
        let rc = unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, fds.as_mut_ptr()) };
        assert_eq!(rc, 0, "socketpair failed: {}", std::io::Error::last_os_error());
        // Close the peer half; the test only hands `fds[0]` to dispatch.
        // SAFETY: `fds[1]` is a fresh descriptor this test exclusively owns.
        unsafe { libc::close(fds[1]) };
        fds[0]
    }

    /// True when `fd` is no longer a valid descriptor (closed) — `fcntl`
    /// reports `EBADF`. A still-open fd returns its flags (>= 0).
    pub(crate) fn fd_is_closed(fd: RawFd) -> bool {
        // SAFETY: `F_GETFD` only reads the descriptor's flags; it never
        // mutates process state and tolerates an invalid `fd` (returns -1).
        let rc = unsafe { libc::fcntl(fd, libc::F_GETFD) };
        rc == -1 && std::io::Error::last_os_error().raw_os_error() == Some(libc::EBADF)
    }
}

#[cfg(test)]
mod tests {
    use std::os::fd::AsRawFd;

    use ripdpi_root_helper_protocol::{CMD_PROTOCOL_PREFLIGHT, COMMAND_DESCRIPTORS, HelperRequest, PROTOCOL_VERSION};

    use super::{DISPATCH_COMMANDS, dispatch_command};

    fn request_for(command: &str) -> HelperRequest {
        HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: command.to_string(),
            params: serde_json::json!({}),
            session_nonce: None,
        }
    }

    #[test]
    fn stale_protocol_request_is_rejected_before_shutdown_dispatch() {
        let mut request = request_for(ripdpi_root_helper_protocol::CMD_SHUTDOWN);
        request.protocol_version = Some(PROTOCOL_VERSION - 1);

        let outcome = dispatch_command(&request, None);

        assert!(!outcome.response.ok);
        assert!(outcome.response.error.as_deref().is_some_and(|error| error.contains("protocol version mismatch")));
        assert!(!outcome.shutdown_requested);
    }

    /// Every command in the protocol descriptor inventory must resolve to a
    /// real dispatch arm — never the `unknown command` catch-all. Each request
    /// carries empty params and no fd, so fd-requiring handlers stop at the
    /// `require_fd` gate and typed-params handlers stop at `decode_params`,
    /// before any privileged syscall — the test needs no root.
    #[test]
    fn every_command_descriptor_has_a_dispatch_handler() {
        for descriptor in COMMAND_DESCRIPTORS {
            let outcome = dispatch_command(&request_for(descriptor.command), None);
            let error = outcome.response.error.clone().unwrap_or_default();
            assert!(
                !error.starts_with("unknown command"),
                "no dispatch handler for `{}` (response error: {error:?})",
                descriptor.command,
            );
        }
    }

    /// The discriminator the coverage test relies on: an unrecognized command
    /// really does fall through to the `unknown command` catch-all.
    #[test]
    fn unknown_command_falls_through_to_the_catch_all() {
        let outcome = dispatch_command(&request_for("totally_unknown_command_v999"), None);
        let error = outcome.response.error.unwrap_or_default();
        assert!(error.starts_with("unknown command"), "unexpected response for an unknown command: {error:?}");
    }

    #[test]
    fn protocol_preflight_is_a_pure_empty_success() {
        let outcome = dispatch_command(&request_for(CMD_PROTOCOL_PREFLIGHT), None);

        assert!(outcome.response.ok);
        assert!(outcome.response.data.is_null());
        assert!(outcome.reply_fd.is_none());
        assert!(!outcome.shutdown_requested);
    }

    /// A descriptor that requires an inbound fd must make dispatch reject a
    /// request that arrives without one — this ties the descriptor's
    /// `requires_inbound_fd` flag to the helper's descriptor pre-validator and
    /// the per-handler `require_fd` gate underneath it.
    #[test]
    fn fd_requiring_commands_reject_a_missing_inbound_fd() {
        for descriptor in COMMAND_DESCRIPTORS {
            if !descriptor.requires_inbound_fd {
                continue;
            }
            let outcome = dispatch_command(&request_for(descriptor.command), None);
            assert!(!outcome.response.ok, "{} must fail without an inbound fd", descriptor.command);
            let error = outcome.response.error.unwrap_or_default();
            assert!(error.contains("fd"), "{} should report a missing-fd error, got: {error:?}", descriptor.command,);
        }
    }

    /// A descriptor that does NOT require an inbound fd must reject a request
    /// that arrives with one, instead of silently leaking the descriptor as
    /// the pre-validation dispatch path used to. The validator-side test pins
    /// the typed error; this test pins the helper end-to-end behavior.
    #[test]
    fn non_fd_commands_reject_an_unexpected_inbound_fd() {
        // `/dev/null` is a cheap, side-effect-free, real fd suitable for
        // exercising the SCM_RIGHTS rejection path. The dispatcher's
        // `close_received_fd` closes it; the test re-opening `/dev/null`
        // never observes a stale descriptor.
        for descriptor in COMMAND_DESCRIPTORS {
            if descriptor.requires_inbound_fd {
                continue;
            }
            let dev_null = std::fs::File::open("/dev/null").expect("open /dev/null");
            let raw_fd = dev_null.as_raw_fd();
            std::mem::forget(dev_null); // give ownership to dispatcher.
            let outcome = dispatch_command(&request_for(descriptor.command), Some(raw_fd));
            assert!(!outcome.response.ok, "{} must reject an unexpected inbound fd", descriptor.command);
            let error = outcome.response.error.unwrap_or_default();
            assert!(
                error.contains("does not accept a stream fd"),
                "{} should report an unexpected-fd error, got: {error:?}",
                descriptor.command,
            );
            assert!(outcome.reply_fd.is_none(), "{} must not return a reply fd on rejection", descriptor.command);
        }
    }

    /// Every dispatch arm in `dispatch_command` has a matching descriptor in
    /// `COMMAND_DESCRIPTORS`. Together with
    /// `every_command_descriptor_has_a_dispatch_handler` this is the
    /// bidirectional coverage pin: no orphan dispatch arm, no orphan
    /// descriptor.
    #[test]
    fn every_dispatch_arm_has_a_descriptor() {
        for command in DISPATCH_COMMANDS {
            assert!(
                COMMAND_DESCRIPTORS.iter().any(|descriptor| descriptor.command == *command),
                "dispatch arm `{command}` has no entry in COMMAND_DESCRIPTORS",
            );
        }
        assert_eq!(
            DISPATCH_COMMANDS.len(),
            COMMAND_DESCRIPTORS.len(),
            "DISPATCH_COMMANDS and COMMAND_DESCRIPTORS must be the same size",
        );
    }

    /// A legacy request JSON still decodes so the helper can reject it with a
    /// precise protocol error before any privileged handler runs.
    #[test]
    fn legacy_request_json_without_version_metadata_is_rejected_before_dispatch() {
        let legacy_shutdown = r#"{"command":"shutdown"}"#;
        let request: HelperRequest = serde_json::from_str(legacy_shutdown).expect("legacy shutdown JSON decodes");
        let outcome = dispatch_command(&request, None);
        assert!(!outcome.response.ok, "legacy shutdown request must fail closed");
        assert!(!outcome.shutdown_requested, "legacy shutdown must not request shutdown");
        assert!(outcome.response.error.as_deref().is_some_and(|error| error.contains("missing protocol_version")));

        let legacy_probe = r#"{"command":"probe_capabilities","params":null}"#;
        let request: HelperRequest = serde_json::from_str(legacy_probe).expect("legacy probe JSON decodes");
        let outcome = dispatch_command(&request, None);
        assert!(!outcome.response.ok, "legacy probe_capabilities request must fail closed");
    }
}
