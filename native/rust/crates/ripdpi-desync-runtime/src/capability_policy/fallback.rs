use std::borrow::Cow;

use ripdpi_config::{DesyncGroup, TcpChainStep};
use ripdpi_proxy_config::ProxyDirectPathCapability;

use crate::strategy_family::{primary_tcp_strategy_family, tcp_fallback_kind_for_strategy};

pub(crate) fn apply_tcp_capability_fallback<'a>(
    group: &'a DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
) -> Cow<'a, DesyncGroup> {
    let Some(capability) = capability else {
        return Cow::Borrowed(group);
    };
    if !capability_requires_desync_fallback(capability) {
        return Cow::Borrowed(group);
    }

    let Some(strategy_family) = primary_tcp_strategy_family(group) else {
        if group.actions.fake_tcp_timestamp_enabled {
            let mut adjusted = group.clone();
            adjusted.actions.fake_tcp_timestamp_enabled = false;
            return Cow::Owned(adjusted);
        }

        return Cow::Borrowed(group);
    };

    let mut adjusted = group.clone();
    let mut changed = false;
    if let Some(fallback_kind) = tcp_fallback_kind_for_strategy(strategy_family)
        && let Some(step) = adjusted.actions.tcp_chain.iter_mut().find(|step| !step.kind().is_tls_prelude())
        && step.kind() != fallback_kind
    {
        *step = TcpChainStep::new(fallback_kind, step.offset())
            .with_activation_filter(step.activation_filter())
            .with_inter_segment_delay_ms(step.inter_segment_delay_ms());
        changed = true;
    }
    if adjusted.actions.fake_tcp_timestamp_enabled {
        adjusted.actions.fake_tcp_timestamp_enabled = false;
        changed = true;
    }

    if changed { Cow::Owned(adjusted) } else { Cow::Borrowed(group) }
}

pub(crate) fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
        || (matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
            && capability.reason_code.as_deref() != Some("NO_TCP_FALLBACK"))
        || matches!(capability.outcome.trim().to_ascii_uppercase().as_str(), "OWNED_STACK_ONLY" | "NO_DIRECT_SOLUTION")
}
