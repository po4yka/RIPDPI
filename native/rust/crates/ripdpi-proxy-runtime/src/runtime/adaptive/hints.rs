use std::io;
use std::net::SocketAddr;

use crate::runtime::state::RuntimeState;

use super::network_scope_key;

pub(in crate::runtime) fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback().note_tcp_success(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback().note_tcp_failure(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback().note_udp_success(network_scope_key(&state.config), group_index, target, host, payload)
}

pub(in crate::runtime) fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.adaptive_feedback().note_udp_failure(network_scope_key(&state.config), group_index, target, host, payload)
}
