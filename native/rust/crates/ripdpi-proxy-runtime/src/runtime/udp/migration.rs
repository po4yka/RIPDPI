use std::io;

use ripdpi_proxy_runtime_adapter::model::config::{should_rebind_udp_source_port, udp_group_socket_settings};

use super::flow::UdpFlowActivationState;
use super::sockets::build_udp_upstream_socket;
use crate::runtime::state::RuntimeState;

pub(super) fn maybe_rebind_udp_source_port(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    inbound_payload: &[u8],
    protect_path: Option<&str>,
) -> io::Result<()> {
    if !should_rebind_udp_source_port(
        &state.config,
        entry.route.group_index,
        entry.quic_migrated,
        entry.session.round_count,
        inbound_payload,
    ) {
        return Ok(());
    }

    // UDP source-port rebind on post-handshake detection.
    //
    // NOTE: True RFC 9000 connection migration (section 9) requires sending a
    // PATH_CHALLENGE frame (type 0x1a) inside an encrypted short-header
    // packet on the new path, then validating the server's PATH_RESPONSE
    // (type 0x1b) containing the same 8-byte echo data before migrating
    // traffic.  Short-header packets are encrypted with 1-RTT keys
    // derived during the handshake; this proxy has no access to those
    // keys and operates on opaque UDP datagrams.  Injecting or parsing
    // QUIC frames is therefore not feasible at this layer without a full
    // QUIC stack implementation.
    //
    // What the rebind below actually achieves: changing the UDP
    // source port/address forces ISP-level DPI to lose the 5-tuple
    // flow record, which is the intended desync effect.  It is NOT a
    // QUIC-layer migration: the server continues to associate traffic
    // with the original connection ID and will not acknowledge the new
    // path until it receives a valid PATH_CHALLENGE from the client
    // application.  Packets already in flight on the old socket are
    // not replayed on the new socket; the QUIC stack in the client
    // application is responsible for retransmission.
    let socket_settings = udp_group_socket_settings(&state.config, entry.route.group_index);
    match build_udp_upstream_socket(entry.current_target, protect_path, socket_settings.bind_low_port) {
        Ok(new_socket) => {
            entry.upstream = new_socket;
            entry.quic_migrated = true;
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_quic_migration_status(
                    entry.current_target,
                    "rebind_only",
                    "udp_source_port_rebind_after_handshake",
                );
            }
            tracing::debug!(
                target = %entry.current_target,
                round = entry.session.round_count,
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
    use ripdpi_proxy_runtime_adapter::model::config::{should_rebind_udp_source_port, RuntimeConfig};

    #[test]
    fn udp_source_rebind_waits_for_short_header_after_two_rounds() {
        let mut config = RuntimeConfig::default();
        config.groups[0].actions.quic_migrate_after_handshake = true;

        assert!(!should_rebind_udp_source_port(&config, 0, true, 2, &[0x40]));
        assert!(!should_rebind_udp_source_port(&config, 0, false, 1, &[0x40]));
        assert!(!should_rebind_udp_source_port(&config, 0, false, 2, &[0xc0]));
        assert!(should_rebind_udp_source_port(&config, 0, false, 2, &[0x40]));
    }
}
