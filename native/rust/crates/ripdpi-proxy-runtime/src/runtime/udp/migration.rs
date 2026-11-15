use std::io;

use super::flow::UdpFlowActivationState;
use super::sockets::build_udp_upstream_socket;
use crate::runtime::state::RuntimeState;

pub(super) fn maybe_rebind_udp_source_port(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    inbound_payload: &[u8],
    protect_path: Option<&str>,
) -> io::Result<()> {
    // An upstream SOCKS association pins its UDP sender for the lifetime of its control TCP.
    if entry.socks_framed()
        || !RuntimeState::should_rebind_udp_flow_source_port(
            entry.source_rebind_policy,
            entry.quic_migrated,
            entry.session.round_count(),
            inbound_payload,
        )
    {
        return Ok(());
    }

    // RFC 9000 sections 9.2-9.3 permit migration to start with a non-probing
    // packet; the peer can then validate the new path. This opaque proxy has
    // no 1-RTT keys and cannot send or verify PATH_CHALLENGE/PATH_RESPONSE.
    // This is only a source-port rebind, not proof of path validation.
    // In-flight packets on the old socket are not replayed; the client QUIC
    // stack must retransmit them.
    let replacement =
        build_udp_upstream_socket(entry.current_target, protect_path, entry.socket_settings.bind_low_port).and_then(
            |socket| {
                if socket.local_addr()? == entry.upstream.local_addr()? {
                    return Err(io::Error::new(io::ErrorKind::AddrInUse, "replacement UDP source tuple is unchanged"));
                }
                Ok(socket)
            },
        );
    match replacement {
        Ok(new_socket) => {
            entry.remote_feedback.reset_socket();
            entry.upstream = new_socket;
            entry.quic_migrated = true;
            state.note_quic_migration_status(
                entry.current_target,
                "rebind_only",
                "udp_source_port_rebind_after_handshake",
            );
            tracing::debug!(
                target = %entry.current_target,
                round = entry.session.round_count(),
                "QUIC UDP source-port rebind (RFC 9000 migration requires QUIC-layer implementation)"
            );
            Ok(())
        }
        Err(err) => {
            tracing::debug!(
                target = %entry.current_target,
                %err,
                "failed to rebind UDP source port after QUIC handshake"
            );
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::runtime::state::RuntimeState;
    use crate::runtime::udp::RuntimeUdpSourceRebindPolicy;

    #[test]
    fn udp_source_rebind_waits_for_short_header_after_two_rounds() {
        let policy = RuntimeUdpSourceRebindPolicy::after_handshake(true);

        assert!(!RuntimeState::should_rebind_udp_flow_source_port(policy, true, 2, &[0x40]));
        assert!(!RuntimeState::should_rebind_udp_flow_source_port(policy, false, 1, &[0x40]));
        assert!(!RuntimeState::should_rebind_udp_flow_source_port(policy, false, 2, &[0xc0]));
        assert!(RuntimeState::should_rebind_udp_flow_source_port(policy, false, 2, &[0x40]));
    }
}
