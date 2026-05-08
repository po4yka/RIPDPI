use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::{
    first_response_settings, relay_group_settings, tcp_rotation_seed, RuntimeTimeoutSettings,
};
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::payload_host_extractor;

use super::super::routing::{emit_failure_classified, note_block_signal_for_failure};
use super::super::state::RuntimeState;
use super::failure_retry::record_stream_relay_success;
use super::stream_copy::{relay_streams, RelayOutboundSettings, RelayStreamSettings, CONNECTION_FREEZE_MARKER};
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
use super::stream_copy_uring;

#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
#[inline(never)]
pub(super) fn relay_with_uring_if_available(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    route: ConnectionRoute,
    session_state: ripdpi_proxy_runtime_adapter::model::session::SessionState,
    success_host: Option<String>,
) -> io::Result<ripdpi_proxy_runtime_adapter::model::session::SessionState> {
    let relay_settings = relay_stream_settings(state, route.group_index)?;
    let uring_driver = ripdpi_io_uring::io_uring_capabilities().send_zc.then(|| state.io_uring.as_ref()).flatten();
    if let Some(driver) = uring_driver.filter(|_| !relay_settings.group.rotation_enabled) {
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
    route: ConnectionRoute,
    session_state: ripdpi_proxy_runtime_adapter::model::session::SessionState,
    success_host: Option<String>,
) -> io::Result<ripdpi_proxy_runtime_adapter::model::session::SessionState> {
    let relay_settings = relay_stream_settings(state, route.group_index)?;
    relay_streams(client, upstream, state, route.group_index, relay_settings, session_state, success_host)
}

fn relay_stream_settings(state: &RuntimeState, group_index: usize) -> io::Result<RelayStreamSettings> {
    let group = relay_group_settings(&state.config, group_index)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    Ok(RelayStreamSettings {
        group,
        outbound: RelayOutboundSettings {
            host_extractor: payload_host_extractor(&state.config),
            first_response: first_response_settings(&state.config),
        },
        rotation_seed: tcp_rotation_seed(&state.config, group_index)?,
    })
}

pub(super) struct RelayResultContext<'a> {
    pub(super) relay_timeouts: RuntimeTimeoutSettings,
    pub(super) target: SocketAddr,
    pub(super) route: &'a ConnectionRoute,
    pub(super) success_recorded: bool,
    pub(super) success_host: Option<&'a str>,
    pub(super) success_payload: Option<&'a [u8]>,
    pub(super) success_strategy_family: Option<&'a str>,
}

#[inline(never)]
pub(super) fn record_relay_result(
    relay_result: &io::Result<ripdpi_proxy_runtime_adapter::model::session::SessionState>,
    state: &RuntimeState,
    context: RelayResultContext<'_>,
) -> io::Result<()> {
    if let Ok(final_state) = relay_result {
        if !context.success_recorded && final_state.recv_count > 0 {
            record_stream_relay_success(
                state,
                context.target,
                context.route,
                context.success_host,
                context.success_payload,
                context.success_strategy_family,
            )?;
        }
    }
    if let Err(err) = relay_result {
        if err.to_string().contains(CONNECTION_FREEZE_MARKER) {
            let failure =
                ripdpi_proxy_runtime_adapter::failure::classify_relay_connection_freeze(context.relay_timeouts);
            note_block_signal_for_failure(state, context.success_host, &failure, None);
            emit_failure_classified(state, context.target, &failure, context.success_host);
        }
    }
    Ok(())
}
