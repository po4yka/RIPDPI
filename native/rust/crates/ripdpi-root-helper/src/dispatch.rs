mod capabilities;
mod experimental;
mod fragmentation;
mod shutdown;
mod tcp_payload;

use std::os::fd::RawFd;

use ripdpi_root_helper_protocol as protocol;
use ripdpi_root_helper_protocol::{
    HelperRequest, CMD_PROBE_CAPABILITIES, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
    CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP,
    CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET, CMD_SEND_SEQOVL_TCP,
    CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
};

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

pub(crate) fn dispatch_command(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    match request.command.as_str() {
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
        other => DispatchOutcome::command(protocol::HelperResponse::error(format!("unknown command: {other}")), None),
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

#[cfg(test)]
mod tests {
    use ripdpi_root_helper_protocol::{HelperRequest, COMMAND_DESCRIPTORS};

    use super::dispatch_command;

    fn request_for(command: &str) -> HelperRequest {
        HelperRequest { command: command.to_string(), params: serde_json::json!({}), session_nonce: None }
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

    /// A descriptor that requires an inbound fd must make dispatch reject a
    /// request that arrives without one — this ties the descriptor's
    /// `requires_inbound_fd` flag to the helper's `require_fd` gate.
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
}
