use std::io;
use std::net::SocketAddr;

use crate::runtime::desync::FakeTtlAttempt;
use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    attempt: Option<FakeTtlAttempt>,
) -> io::Result<()> {
    if let Some(attempt) = attempt {
        state.note_adaptive_fake_ttl_success(target, group_index, host, attempt)?;
    }
    Ok(())
}

pub(in crate::runtime) fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    attempt: Option<FakeTtlAttempt>,
) -> io::Result<()> {
    if let Some(attempt) = attempt {
        state.note_adaptive_fake_ttl_failure(target, group_index, host, attempt)?;
    }
    Ok(())
}

pub(in crate::runtime) fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
    attempt: Option<FakeTtlAttempt>,
) -> io::Result<()> {
    if let Some(attempt) = attempt {
        state.note_server_ttl(target, group_index, host, observed_ttl, attempt)?;
    }
    Ok(())
}
