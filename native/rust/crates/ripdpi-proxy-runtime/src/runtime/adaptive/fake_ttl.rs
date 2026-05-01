use std::io;
use std::net::SocketAddr;

use ripdpi_config::DesyncGroup;

use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn resolve_adaptive_fake_ttl(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
) -> io::Result<Option<u8>> {
    let Some(auto_ttl) = group.actions.auto_ttl else {
        return Ok(None);
    };
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    Ok(Some(resolver.resolve(group_index, target, host, auto_ttl, group.actions.ttl)))
}

pub(in crate::runtime) fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    resolver.note_success(group_index, target, host);
    Ok(())
}

pub(in crate::runtime) fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    resolver.note_failure(group_index, target, host);
    Ok(())
}

pub(in crate::runtime) fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    resolver.note_server_ttl(group_index, target, host, observed_ttl);
    Ok(())
}
