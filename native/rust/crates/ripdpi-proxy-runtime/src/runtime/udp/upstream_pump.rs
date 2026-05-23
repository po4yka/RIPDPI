use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
#[cfg(unix)]
use std::os::fd::{AsRawFd, RawFd};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;

use super::encode_socks5_udp_packet;
use super::feedback::note_udp_first_response_success;
use super::flow::UdpFlowActivationState;
use super::migration::maybe_rebind_udp_source_port;
use crate::runtime::state::RuntimeState;

type UdpFlowKey = (SocketAddr, SocketAddr);

pub(super) fn pump_udp_upstream_responses(
    state: &RuntimeState,
    client_relay: &UdpSocket,
    upstream_buffer: &mut [u8],
    flow_state: &mut HashMap<UdpFlowKey, UdpFlowActivationState>,
    protect_path: Option<&str>,
) -> io::Result<bool> {
    let mut made_progress = false;
    for (client_addr, logical_target) in ready_udp_flow_keys(flow_state)? {
        let Some(entry) = flow_state.get_mut(&(client_addr, logical_target)) else {
            continue;
        };
        match entry.upstream.recv(upstream_buffer) {
            Ok(n) => {
                made_progress = true;
                let now = Instant::now();
                entry.last_used = now;
                entry.session.observe_upstream_response(&upstream_buffer[..n]);
                note_udp_first_response_success(state, entry)?;
                maybe_rebind_udp_source_port(state, entry, &upstream_buffer[..n], protect_path)?;
                let packet = encode_socks5_udp_packet(entry.current_target, &upstream_buffer[..n]);
                client_relay.send_to(&packet, client_addr)?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) if udp_platform::is_connection_refused(&err) => {}
            Err(err) => return Err(err),
        }
    }
    Ok(made_progress)
}

#[cfg(unix)]
fn ready_udp_flow_keys(flow_state: &HashMap<UdpFlowKey, UdpFlowActivationState>) -> io::Result<Vec<UdpFlowKey>> {
    let entries = flow_state.iter().map(|(&key, entry)| (key, entry.upstream.as_raw_fd())).collect::<Vec<_>>();
    ready_udp_poll_keys(&entries)
}

#[cfg(not(unix))]
fn ready_udp_flow_keys(flow_state: &HashMap<UdpFlowKey, UdpFlowActivationState>) -> io::Result<Vec<UdpFlowKey>> {
    Ok(flow_state.keys().copied().collect())
}

#[cfg(unix)]
pub(super) fn ready_udp_poll_keys(entries: &[(UdpFlowKey, RawFd)]) -> io::Result<Vec<UdpFlowKey>> {
    if entries.is_empty() {
        return Ok(Vec::new());
    }

    let mut pollfds =
        entries.iter().map(|&(_, fd)| libc::pollfd { fd, events: libc::POLLIN, revents: 0 }).collect::<Vec<_>>();
    // SAFETY: `pollfds` is a live mutable buffer for the full call, and
    // `libc::poll` only reads/writes within the pointer plus exact length.
    let result = unsafe { libc::poll(pollfds.as_mut_ptr(), pollfds.len() as libc::nfds_t, 0) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    if result == 0 {
        return Ok(Vec::new());
    }

    const READY_EVENTS: libc::c_short = libc::POLLIN | libc::POLLERR | libc::POLLHUP | libc::POLLNVAL;
    Ok(entries
        .iter()
        .zip(pollfds.iter())
        .filter_map(|(&(key, _), pollfd)| (pollfd.revents & READY_EVENTS != 0).then_some(key))
        .collect())
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
    RuntimeState::execute_udp_desync_actions(
        &entry.upstream,
        entry.current_target,
        entry.packet_settings,
        protect_path,
        &actions,
    )
}
