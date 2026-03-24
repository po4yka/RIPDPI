use std::io;
use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;

use super::state::RuntimeState;

pub(super) fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.adaptive.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
}

pub(super) fn resolve_adaptive_fake_ttl(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
) -> io::Result<Option<u8>> {
    let Some(auto_ttl) = group.auto_ttl else {
        return Ok(None);
    };
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    Ok(Some(resolver.resolve(group_index, target, host, auto_ttl, group.ttl)))
}

pub(super) fn resolve_adaptive_tcp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    Ok(resolver.resolve_tcp_hints(network_scope_key(&state.config), group_index, target, host, group, payload))
}

pub(super) fn resolve_adaptive_udp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    Ok(resolver.resolve_udp_hints(network_scope_key(&state.config), group_index, target, host, group, payload))
}

pub(super) fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_success(group_index, target, host);
    Ok(())
}

pub(super) fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_failure(group_index, target, host);
    Ok(())
}

pub(super) fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_server_ttl(group_index, target, host, observed_ttl);
    Ok(())
}

pub(super) fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_tcp_success(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

pub(super) fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_tcp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

pub(super) fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_udp_success(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

pub(super) fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_udp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}
