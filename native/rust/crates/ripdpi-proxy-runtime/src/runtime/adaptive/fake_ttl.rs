use std::io;
use std::net::SocketAddr;

use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    state.adaptive_feedback().note_fake_ttl_success(group_index, target, host)
}

pub(in crate::runtime) fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    state.adaptive_feedback().note_fake_ttl_failure(group_index, target, host)
}

pub(in crate::runtime) fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    state.adaptive_feedback().note_server_ttl(group_index, target, host, observed_ttl)
}
