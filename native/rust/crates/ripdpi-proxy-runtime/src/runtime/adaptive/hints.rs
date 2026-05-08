use std::io;
use std::net::SocketAddr;

use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.note_adaptive_tcp_success(target, group_index, host, payload)
}

pub(in crate::runtime) fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.note_adaptive_tcp_failure(target, group_index, host, payload)
}

pub(in crate::runtime) fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.note_adaptive_udp_success(target, group_index, host, payload)
}

pub(in crate::runtime) fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    state.note_adaptive_udp_failure(target, group_index, host, payload)
}
