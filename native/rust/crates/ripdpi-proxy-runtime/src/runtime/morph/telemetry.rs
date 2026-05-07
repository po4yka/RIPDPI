use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::proxy_config::ProxyMorphPolicy;

use crate::runtime::state::RuntimeState;

pub(super) fn emit_morph_hint_applied(
    state: &RuntimeState,
    policy: Option<&ProxyMorphPolicy>,
    target: SocketAddr,
    family: Option<String>,
) {
    let Some(telemetry) = &state.telemetry else {
        return;
    };
    let Some(policy) = policy else {
        return;
    };
    let Some(family) = family.as_deref().filter(|value| !value.is_empty()) else {
        return;
    };
    telemetry.on_morph_hint_applied(target, policy.id.as_str(), family);
}

pub(super) fn emit_morph_rollback(
    state: &RuntimeState,
    policy: Option<&ProxyMorphPolicy>,
    target: SocketAddr,
    reason: impl AsRef<str>,
) {
    let Some(telemetry) = &state.telemetry else {
        return;
    };
    let Some(policy) = policy else {
        return;
    };
    let reason = reason.as_ref();
    if reason.is_empty() {
        return;
    }
    telemetry.on_morph_rollback(target, policy.id.as_str(), reason);
}
