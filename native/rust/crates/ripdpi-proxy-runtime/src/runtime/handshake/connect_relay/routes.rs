use std::net::{SocketAddr, TcpStream};

use super::super::super::state::RuntimeState;
use super::ConnectRelayError;
use crate::SameSniProfileGuard;
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_session_limit::ExitIpSessionGuard;

pub(super) struct UpstreamRoute {
    pub(super) upstream: TcpStream,
    pub(super) route: RuntimeConnectionRoute,
    pub(super) seed_request: Option<Vec<u8>>,
    /// Per-exit-IP concurrent-session slot held for this connection. Travels
    /// with `upstream` so RAII keeps the slot reserved for the whole relay
    /// session and frees it when this struct is dropped after `relay()` returns.
    pub(super) cap_guard: Option<ExitIpSessionGuard>,
    pub(super) same_sni_guard: Option<SameSniProfileGuard>,
}

pub(super) fn connect_immediate_route(
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
) -> Result<UpstreamRoute, ConnectRelayError> {
    let same_sni_guard = acquire_same_sni_guard(state, host_hint.as_deref())?;
    let (upstream, route, cap_guard) =
        super::super::super::routing::connect_target(target, state, None, false, host_hint)
            .map_err(|err| ConnectRelayError::new(err, false))?;
    Ok(UpstreamRoute { upstream, route, seed_request: None, cap_guard, same_sni_guard })
}

pub(super) fn connect_delayed_route(
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    route: RuntimeConnectionRoute,
    payload: Vec<u8>,
) -> Result<UpstreamRoute, ConnectRelayError> {
    let host = state.extract_relay_payload_host(&payload).or(host_hint);
    let same_sni_guard = acquire_same_sni_guard(state, host.as_deref())?;
    let (upstream, route, cap_guard) =
        super::super::super::routing::connect_target_with_route(target, state, route, Some(&payload), host)
            .map_err(|err| ConnectRelayError::with_seed_request(err, true, Some(payload.clone())))?;
    Ok(UpstreamRoute { upstream, route, seed_request: Some(payload), cap_guard, same_sni_guard })
}

pub(super) fn connect_ws_seed_route(
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    seed_request: Vec<u8>,
) -> Result<UpstreamRoute, ConnectRelayError> {
    let same_sni_guard = acquire_same_sni_guard(state, host_hint.as_deref())?;
    let seed_request = (!seed_request.is_empty()).then_some(seed_request);
    let (upstream, route, cap_guard) =
        super::super::super::routing::connect_target(target, state, seed_request.as_deref(), true, host_hint)
            .map_err(|err| ConnectRelayError::new(err, true))?;
    Ok(UpstreamRoute { upstream, route, seed_request, cap_guard, same_sni_guard })
}

fn acquire_same_sni_guard(
    state: &RuntimeState,
    host: Option<&str>,
) -> Result<Option<SameSniProfileGuard>, ConnectRelayError> {
    let Some(host) = host else { return Ok(None) };
    state.try_acquire_pass_through_sni_session(host).map(Some).ok_or_else(|| {
        ConnectRelayError::new(
            std::io::Error::new(std::io::ErrorKind::WouldBlock, "same-SNI/profile concurrency cap reached"),
            false,
        )
    })
}
