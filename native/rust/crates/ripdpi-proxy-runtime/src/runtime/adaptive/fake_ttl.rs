use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::config::DesyncGroup;

use crate::runtime::state::RuntimeState;

use super::network_scope_key;

pub(in crate::runtime) fn resolve_adaptive_fake_ttl(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
) -> io::Result<Option<u8>> {
    state.adaptive_hints.resolve_fake_ttl(network_scope_key(&state.config), group_index, target, host, group)
}

pub(in crate::runtime) fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    state.adaptive_feedback.note_fake_ttl_success(network_scope_key(&state.config), group_index, target, host)
}

pub(in crate::runtime) fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    state.adaptive_feedback.note_fake_ttl_failure(network_scope_key(&state.config), group_index, target, host)
}

pub(in crate::runtime) fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    state.adaptive_feedback.note_server_ttl(network_scope_key(&state.config), group_index, target, host, observed_ttl)
}
