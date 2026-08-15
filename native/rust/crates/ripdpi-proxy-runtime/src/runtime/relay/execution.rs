use std::io;
use std::net::{SocketAddr, TcpStream};

use super::super::routing::{emit_failure_classified, note_block_signal_for_failure};
use super::super::state::RuntimeState;
use super::failure_retry::record_stream_relay_success;
use super::session::RelaySession;
use super::stream_copy::{CONNECTION_FREEZE_MARKER, RelayStreamSettings, relay_streams};
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
use super::stream_copy_uring;
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeRelayTimeouts};

#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
#[inline(never)]
pub(super) fn relay_with_uring_if_available(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    route: RuntimeConnectionRoute,
    session_state: RelaySession,
    success_host: Option<String>,
) -> io::Result<RelaySession> {
    let _active_upstream = state.register_active_upstream_tcp_socket(&upstream)?;
    let relay_settings = relay_stream_settings(state, route.group_index)?;
    let uring_driver =
        ripdpi_io_uring::io_uring_capabilities().fixed_buffers.then(|| state.io_uring_driver()).flatten();
    if let Some(driver) = uring_driver.filter(|_| !relay_settings.group.rotation_enabled()) {
        return stream_copy_uring::relay_streams_uring(
            client,
            upstream,
            state,
            route.group_index,
            relay_settings,
            session_state,
            success_host,
            driver,
        );
    }
    relay_streams(client, upstream, state, route.group_index, relay_settings, session_state, success_host)
}

#[cfg(not(all(feature = "io-uring", any(target_os = "linux", target_os = "android"))))]
#[inline(never)]
pub(super) fn relay_with_uring_if_available(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    route: RuntimeConnectionRoute,
    session_state: RelaySession,
    success_host: Option<String>,
) -> io::Result<RelaySession> {
    let _active_upstream = state.register_active_upstream_tcp_socket(&upstream)?;
    let relay_settings = relay_stream_settings(state, route.group_index)?;
    relay_streams(client, upstream, state, route.group_index, relay_settings, session_state, success_host)
}

fn relay_stream_settings(state: &RuntimeState, group_index: usize) -> io::Result<RelayStreamSettings> {
    Ok(RelayStreamSettings {
        group: state.relay_group(group_index)?,
        rotation_seed: state.relay_rotation_seed(group_index)?,
    })
}

pub(super) struct RelayResultContext<'a> {
    pub(super) relay_timeouts: RuntimeRelayTimeouts,
    pub(super) target: SocketAddr,
    pub(super) route: &'a RuntimeConnectionRoute,
    pub(super) success_recorded: bool,
    pub(super) success_host: Option<&'a str>,
    pub(super) success_payload: Option<&'a [u8]>,
    pub(super) success_strategy_family: Option<&'a str>,
    pub(super) primary_strategy_family: Option<&'a str>,
}

#[inline(never)]
pub(super) fn record_relay_result(
    relay_result: &io::Result<RelaySession>,
    state: &RuntimeState,
    context: RelayResultContext<'_>,
) -> io::Result<()> {
    if let Ok(final_state) = relay_result
        && !context.success_recorded
        && final_state.has_inbound_payload()
    {
        record_stream_relay_success(
            state,
            context.target,
            context.route,
            context.success_host,
            context.success_payload,
            context.success_strategy_family,
            context.primary_strategy_family,
        )?;
    }
    if let Err(err) = relay_result
        && err.to_string().contains(CONNECTION_FREEZE_MARKER)
    {
        let failure = RuntimeState::classify_relay_connection_freeze(context.relay_timeouts);
        note_block_signal_for_failure(state, context.success_host, &failure, None);
        emit_failure_classified(state, context.target, &failure, context.success_host);
    }
    Ok(())
}
