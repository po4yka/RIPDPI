// Adaptive hint resolution for the runtime send path.
//
// Hint priority chain (highest to lowest):
//
// 1. Strategy evolver hints (`StrategyEvolver::suggest_hints`) -- session-wide,
//    when `config.adaptive.strategy_evolution` is enabled. Overrides per-flow
//    tuning for every dimension the evolver sets.
// 2. Per-flow adaptive hints (`AdaptivePlannerResolver::resolve_*_hints`) --
//    per (host, group, flow-kind) tuple. Used when the evolver is disabled or
//    returns `None`.
// 3. Group defaults -- static values from the `DesyncGroup` configuration.

use std::io;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::DesyncGroup;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_runtime_adaptive::strategy_context::{
    merge_udp_hints_with_capability, tcp_learning_context, udp_learning_context,
};
use ripdpi_runtime_api::RuntimeTelemetrySink;

use super::morph::{apply_tcp_morph_policy_to_hints, apply_udp_morph_policy_to_hints, emit_morph_rollback};
use super::state::RuntimeState;
use ripdpi_runtime_policy::direct_path_learning::DirectPathLearningObserver;
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

pub(super) use ripdpi_runtime_adaptive::strategy_context::{
    capability_blocks_transport, direct_path_capability_for_route, direct_path_capability_for_targets,
    network_scope_key,
};

pub(super) fn resolve_adaptive_fake_ttl(
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

pub(super) fn resolve_adaptive_tcp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    Ok(apply_tcp_morph_policy_to_hints(
        state,
        resolver.resolve_tcp_hints(network_scope_key(&state.config), group_index, target, host, group, payload),
    ))
}

pub(super) fn resolve_adaptive_udp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    let hints = apply_udp_morph_policy_to_hints(
        state,
        resolver.resolve_udp_hints(network_scope_key(&state.config), group_index, target, host, group, payload),
    );
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let merged = merge_udp_hints_with_capability(hints, capability);
    record_morph_rollback(state, target, hints, merged);
    Ok(merged)
}

pub(super) fn note_adaptive_fake_ttl_success(
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

pub(super) fn note_adaptive_fake_ttl_failure(
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

pub(super) fn note_server_ttl_for_route(
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

pub(super) fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_tcp_success(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

pub(super) fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_tcp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

pub(super) fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_udp_success(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

pub(super) fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_udp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

// ---------------------------------------------------------------------------
// Evolver-aware wrappers (priority level 1 → level 2 fallback)
// ---------------------------------------------------------------------------

pub(super) fn resolve_tcp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    if !state.config.adaptive.strategy_evolution {
        return resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload);
    }
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.set_learning_context(tcp_learning_context(
            &state.config,
            state.runtime_context.as_ref(),
            target,
            host,
            payload,
        ));
        if let Some(hints) = evolver.peek_hints() {
            return Ok(apply_tcp_morph_policy_to_hints(state, hints));
        }
        if let Some(hints) = evolver.suggest_hints() {
            return Ok(apply_tcp_morph_policy_to_hints(state, hints));
        }
    }
    resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload)
}

pub(super) fn resolve_udp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    if !state.config.adaptive.strategy_evolution {
        return resolve_adaptive_udp_hints(state, target, group_index, group, host, payload);
    }
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.set_learning_context(udp_learning_context(
            &state.config,
            state.runtime_context.as_ref(),
            target,
            host,
            payload,
        ));
        if let Some(hints) = evolver.peek_hints() {
            let hints = apply_udp_morph_policy_to_hints(state, hints);
            let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
            let merged = merge_udp_hints_with_capability(hints, capability);
            record_morph_rollback(state, target, hints, merged);
            return Ok(merged);
        }
        if let Some(hints) = evolver.suggest_hints() {
            let hints = apply_udp_morph_policy_to_hints(state, hints);
            let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
            let merged = merge_udp_hints_with_capability(hints, capability);
            record_morph_rollback(state, target, hints, merged);
            return Ok(merged);
        }
    }
    resolve_adaptive_udp_hints(state, target, group_index, group, host, payload)
}

pub(super) fn note_evolver_success(state: &RuntimeState, latency_ms: u64) {
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.record_success(latency_ms);
    }
}

pub(super) fn note_evolver_failure(state: &RuntimeState, class: ripdpi_failure_classifier::FailureClass) {
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.record_failure(class);
    }
}

fn record_morph_rollback(
    state: &RuntimeState,
    target: SocketAddr,
    before: AdaptivePlannerHints,
    after: AdaptivePlannerHints,
) {
    if before.udp_burst_profile != after.udp_burst_profile || before.quic_fake_profile != after.quic_fake_profile {
        emit_morph_rollback(state, target, "direct_path_capability_downgrade");
    }
}

struct RuntimeTelemetryDirectPathObserver<'a>(&'a dyn RuntimeTelemetrySink);

impl DirectPathLearningObserver for RuntimeTelemetryDirectPathObserver<'_> {
    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        self.0.on_direct_path_learning_signal(authority, ip_set_digest, event, strategy_family);
    }
}

fn direct_path_observer(state: &RuntimeState) -> Option<RuntimeTelemetryDirectPathObserver<'_>> {
    state.telemetry.as_deref().map(RuntimeTelemetryDirectPathObserver)
}

pub(super) fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|value| value.as_millis() as i64).unwrap_or(0)
}

pub(super) fn note_direct_path_transport_attempt(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    transport: TransportProtocol,
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_transport_attempt(host, targets, transport);
    Ok(())
}

pub(super) fn note_direct_path_udp_suppressed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_udp_suppressed(host, targets, now_millis().max(0) as u64);
    Ok(())
}

pub(super) fn note_direct_path_udp_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_udp_failure(host, targets);
    Ok(())
}

pub(super) fn note_direct_path_quic_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    let observer = direct_path_observer(state);
    learner.note_quic_success(observer.as_ref().map(|value| value as &dyn DirectPathLearningObserver), host, targets);
    Ok(())
}

pub(super) fn note_direct_path_tcp_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    strategy_family: Option<&str>,
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    let observer = direct_path_observer(state);
    learner.note_tcp_success(
        observer.as_ref().map(|value| value as &dyn DirectPathLearningObserver),
        host,
        targets,
        strategy_family,
    );
    Ok(())
}

pub(super) fn note_direct_path_tls_post_client_hello_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_tls_post_client_hello_failure(host, targets);
    Ok(())
}

pub(super) fn note_direct_path_all_ips_failed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    let observer = direct_path_observer(state);
    learner.note_all_ips_failed(observer.as_ref().map(|value| value as &dyn DirectPathLearningObserver), host, targets);
    Ok(())
}

pub(super) fn emit_due_direct_path_learning_timeouts(state: &RuntimeState) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    let observer = direct_path_observer(state);
    learner.emit_due_timeouts(
        observer.as_ref().map(|value| value as &dyn DirectPathLearningObserver),
        now_millis().max(0) as u64,
    );
    Ok(())
}
