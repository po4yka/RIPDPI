use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::failure::{block_signal_from_failure, ClassifiedFailure};
use ripdpi_proxy_runtime_adapter::model::decision::{
    classify_response_failure as classify_policy_response_failure, response_requires_dns_tampering_evidence,
    DnsTamperingEvidence,
};
use ripdpi_proxy_runtime_adapter::ws_bootstrap::encrypted_dns_ip_answers_for_host;

use super::super::state::RuntimeState;

mod advance;
mod cache;
mod feedback;
mod telemetry;

pub(in crate::runtime) use advance::advance_route_for_failure;
pub(in crate::runtime) use ripdpi_proxy_runtime_adapter::failure::should_track_strategy_target;
pub(in crate::runtime) use telemetry::emit_failure_classified;

pub(in crate::runtime) fn note_block_signal_for_failure(
    state: &RuntimeState,
    host: Option<&str>,
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) {
    let Some(host) = host else {
        return;
    };
    let Some(signal) = block_signal_from_failure(failure, tcp_total_retransmissions) else {
        return;
    };
    let confirmation_allowed = state
        .control
        .as_ref()
        .and_then(|control| control.current_network_snapshot())
        .is_none_or(|snapshot| snapshot.validated && !snapshot.captive_portal);
    state.policy().note_block_signal(
        &state.config,
        host,
        signal.signal,
        signal.provider.as_deref(),
        confirmation_allowed,
    );
}

pub(in crate::runtime) fn classify_response_failure(
    state: &RuntimeState,
    target: SocketAddr,
    request: &[u8],
    response: &[u8],
    host: Option<&str>,
) -> Option<ClassifiedFailure> {
    let answer_set = if response_requires_dns_tampering_evidence(request, response) {
        host.and_then(|value| {
            encrypted_dns_ip_answers_for_host(
                value,
                state.runtime_context.as_ref(),
                state.response_failure_evidence_settings.protect_path.as_deref(),
            )
            .ok()
        })
    } else {
        None
    };
    let dns_evidence = host.zip(answer_set.as_ref()).map(|(value, answers)| DnsTamperingEvidence {
        host: value,
        target_ip: target.ip(),
        answers: &answers.answers,
        resolver_label: &answers.label,
    });
    classify_policy_response_failure(request, response, dns_evidence)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn synthetic_tunnel_dns_targets_do_not_participate_in_strategy_tracking() {
        assert!(!should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 853))));
        assert!(!should_track_strategy_target(SocketAddr::from(([198, 19, 42, 7], 853))));
        assert!(should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 443))));
        assert!(should_track_strategy_target(SocketAddr::from(([142, 251, 127, 84], 443))));
    }
}

#[cfg(test)]
#[allow(clippy::items_after_test_module)]
pub(in crate::runtime) fn trigger_flag(trigger: ripdpi_proxy_runtime_adapter::model::session::TriggerEvent) -> u32 {
    ripdpi_proxy_runtime_adapter::response_triggers::response_trigger_flag(trigger)
}
