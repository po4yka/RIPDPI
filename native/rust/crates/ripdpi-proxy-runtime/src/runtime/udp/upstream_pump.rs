use std::collections::HashMap;
use std::io;
use std::net::UdpSocket;
#[cfg(unix)]
use std::os::fd::{AsRawFd, RawFd};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;

use super::encode_socks5_udp_packet_with_resolved_host_into;
use super::feedback::note_udp_first_response_success;
use super::flow::{UdpFlowActivationState, UdpFlowKey};
use super::migration::maybe_rebind_udp_source_port;
use crate::runtime::state::RuntimeState;

#[derive(Default)]
pub(super) struct UdpUpstreamPollScratch {
    #[cfg(unix)]
    entries: Vec<(UdpFlowKey, RawFd)>,
    #[cfg(unix)]
    pollfds: Vec<libc::pollfd>,
    ready_keys: Vec<UdpFlowKey>,
}

pub(super) fn pump_udp_upstream_responses(
    state: &RuntimeState,
    client_relay: &UdpSocket,
    upstream_buffer: &mut [u8],
    encode_buffer: &mut Vec<u8>,
    flow_state: &mut HashMap<UdpFlowKey, UdpFlowActivationState>,
    poll_scratch: &mut UdpUpstreamPollScratch,
    protect_path: Option<&str>,
) -> io::Result<bool> {
    let mut made_progress = false;
    for key in ready_udp_flow_keys(flow_state, poll_scratch)?.to_vec() {
        let Some(entry) = flow_state.get_mut(&key) else {
            continue;
        };
        match entry.upstream.recv(upstream_buffer) {
            Ok(n) => {
                made_progress = true;
                let now = Instant::now();
                entry.last_used = now;
                // Upstream-SOCKS5 relays wrap each reply in an RFC 1928 UDP
                // header; strip it so downstream logic sees the bare payload,
                // exactly as the direct path already does. A relay reply we
                // cannot parse is dropped rather than forwarded as garbage.
                let response = if entry.socks_framed() {
                    let Some(payload) = strip_socks5_udp_header(&upstream_buffer[..n]) else {
                        continue;
                    };
                    payload
                } else {
                    &upstream_buffer[..n]
                };
                entry.session.observe_upstream_response(response);
                note_udp_first_response_success(state, entry)?;
                maybe_rebind_udp_source_port(state, entry, response, protect_path)?;
                if key.preserve_host_in_response
                    && let Some(host) = key.host.as_deref()
                {
                    encode_socks5_udp_packet_with_resolved_host_into(
                        encode_buffer,
                        entry.logical_target,
                        host,
                        response,
                    );
                } else {
                    RuntimeState::encode_socks5_udp_packet_into(encode_buffer, entry.logical_target, response);
                }
                client_relay.send_to(encode_buffer, key.client)?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) if udp_platform::is_connection_refused(&err) => {}
            Err(err) => return Err(err),
        }
    }
    Ok(made_progress)
}

/// Strip the RFC 1928 UDP reply header (`RSV=0 FRAG=0 ATYP DST.ADDR DST.PORT`)
/// from a SOCKS5 relay datagram, returning the bare payload. Returns `None` for
/// non-zero `FRAG` (fragmentation unsupported) or a malformed/truncated header.
fn strip_socks5_udp_header(packet: &[u8]) -> Option<&[u8]> {
    if packet.len() < 4 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
        return None;
    }
    let payload_offset = match packet[3] {
        0x01 => 10,
        0x04 => 22,
        0x03 => {
            let len = *packet.get(4)? as usize;
            5 + len + 2
        }
        _ => return None,
    };
    packet.get(payload_offset..)
}

#[cfg(unix)]
fn ready_udp_flow_keys<'a>(
    flow_state: &HashMap<UdpFlowKey, UdpFlowActivationState>,
    scratch: &'a mut UdpUpstreamPollScratch,
) -> io::Result<&'a [UdpFlowKey]> {
    scratch.entries.clear();
    scratch.entries.extend(flow_state.iter().map(|(key, entry)| (key.clone(), entry.upstream.as_raw_fd())));
    ready_udp_poll_keys(&scratch.entries, &mut scratch.pollfds, &mut scratch.ready_keys)?;
    Ok(&scratch.ready_keys)
}

#[cfg(not(unix))]
fn ready_udp_flow_keys<'a>(
    flow_state: &HashMap<UdpFlowKey, UdpFlowActivationState>,
    scratch: &'a mut UdpUpstreamPollScratch,
) -> io::Result<&'a [UdpFlowKey]> {
    scratch.ready_keys.clear();
    scratch.ready_keys.extend(flow_state.keys().cloned());
    Ok(&scratch.ready_keys)
}

#[cfg(unix)]
pub(super) fn ready_udp_poll_keys(
    entries: &[(UdpFlowKey, RawFd)],
    pollfds: &mut Vec<libc::pollfd>,
    ready_keys: &mut Vec<UdpFlowKey>,
) -> io::Result<()> {
    pollfds.clear();
    ready_keys.clear();
    if entries.is_empty() {
        return Ok(());
    }

    pollfds.extend(entries.iter().map(|(_, fd)| libc::pollfd { fd: *fd, events: libc::POLLIN, revents: 0 }));
    // SAFETY: `pollfds` is a live mutable buffer for the full call, and
    // `libc::poll` only reads/writes within the pointer plus exact length.
    let result = unsafe { libc::poll(pollfds.as_mut_ptr(), pollfds.len() as libc::nfds_t, 0) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    if result == 0 {
        return Ok(());
    }

    const READY_EVENTS: libc::c_short = libc::POLLIN | libc::POLLERR | libc::POLLHUP | libc::POLLNVAL;
    ready_keys.extend(
        entries
            .iter()
            .zip(pollfds.iter())
            .filter(|(_, pollfd)| pollfd.revents & READY_EVENTS != 0)
            .map(|((key, _), _)| key.clone()),
    );
    Ok(())
}

pub(super) fn send_udp_flow_payload(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    payload: &[u8],
    now: Instant,
    protect_path: Option<&str>,
) -> io::Result<()> {
    entry.last_used = now;
    entry.payload.clear();
    entry.payload.extend_from_slice(payload);
    entry.awaiting_response = true;
    let progress = entry.session.observe_datagram_outbound(payload);
    let actions = state.plan_udp_flow_actions(
        entry.route.group_index,
        payload,
        progress,
        entry.host.as_deref(),
        entry.current_target,
        entry.packet_settings.default_ttl,
    )?;
    let outcome = match RuntimeState::execute_udp_desync_actions(
        &entry.upstream,
        entry.current_target,
        entry.packet_settings,
        protect_path,
        entry.socks_framed(),
        &actions,
        payload,
    ) {
        Ok(outcome) => outcome,
        Err(error) => {
            state.record_udp_desync_failure_evidence(
                entry.attempt_token.as_ref(),
                entry.execution_family,
                error.outcome,
            );
            return Err(error.into_io_error());
        }
    };
    state.record_udp_desync_execution_evidence(entry.attempt_token.as_ref(), entry.execution_family, outcome);
    Ok(())
}
