pub use ripdpi_proxy_config::*;

use std::sync::Mutex as StdMutex;

pub struct NetworkReprobeTracker {
    last_identity: StdMutex<Option<String>>,
}

impl NetworkReprobeTracker {
    pub fn new() -> Self {
        Self { last_identity: StdMutex::new(None) }
    }

    pub fn check_snapshot(&self, snapshot: &NetworkSnapshot) -> bool {
        let identity = network_snapshot_identity(snapshot);
        let mut last = self.last_identity.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if last.as_deref() == Some(&identity) {
            return false;
        }
        let is_initial = last.is_none();
        *last = Some(identity);
        !is_initial
    }
}

impl Default for NetworkReprobeTracker {
    fn default() -> Self {
        Self::new()
    }
}

pub fn network_snapshot_identity(snapshot: &NetworkSnapshot) -> String {
    let mut id = snapshot.transport.clone();
    if let Some(ref wifi) = snapshot.wifi {
        id.push(':');
        id.push_str(&wifi.ssid_hash);
    }
    if let Some(ref cellular) = snapshot.cellular {
        id.push(':');
        id.push_str(&cellular.operator_code);
        id.push(':');
        id.push_str(&cellular.generation);
    }
    for dns in &snapshot.dns_servers {
        id.push(',');
        id.push_str(dns);
    }
    id
}

pub fn morph_policy(context: Option<&ProxyRuntimeContext>) -> Option<&ProxyMorphPolicy> {
    context?.morph_policy.as_ref()
}

pub fn morph_policy_id(policy: &ProxyMorphPolicy) -> &str {
    policy.id.as_str()
}

pub fn apply_udp_morph_policy_to_hints(
    policy: Option<&ProxyMorphPolicy>,
    hints: super::desync::AdaptivePlannerHints,
) -> super::desync::AdaptivePlannerHints {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
}

pub fn apply_tcp_morph_policy_to_group(
    policy: Option<&ProxyMorphPolicy>,
    group: &super::config::DesyncGroup,
    payload: &[u8],
    hints: super::desync::AdaptivePlannerHints,
) -> super::config::DesyncGroup {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::apply_tcp_morph_policy_to_group(
        policy, group, payload, hints,
    )
}

pub fn tcp_morph_hint_family(
    policy: Option<&ProxyMorphPolicy>,
    payload: &[u8],
    hints: super::desync::AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::tcp_morph_hint_family(policy, payload, hints)
}

pub fn udp_morph_hint_family(
    policy: Option<&ProxyMorphPolicy>,
    hints: super::desync::AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::udp_morph_hint_family(policy, hints)
}

pub fn emit_morph_hint_applied(
    telemetry: Option<&dyn super::runtime_api::RuntimeTelemetrySink>,
    policy: Option<&ProxyMorphPolicy>,
    target: std::net::SocketAddr,
    family: Option<String>,
) {
    let Some(telemetry) = telemetry else {
        return;
    };
    let Some(policy) = policy else {
        return;
    };
    let Some(family) = family.as_deref().filter(|value| !value.is_empty()) else {
        return;
    };
    telemetry.on_morph_hint_applied(target, morph_policy_id(policy), family);
}

pub fn emit_morph_rollback(
    telemetry: Option<&dyn super::runtime_api::RuntimeTelemetrySink>,
    policy: Option<&ProxyMorphPolicy>,
    target: std::net::SocketAddr,
    reason: impl AsRef<str>,
) {
    let Some(telemetry) = telemetry else {
        return;
    };
    let Some(policy) = policy else {
        return;
    };
    let reason = reason.as_ref();
    if reason.is_empty() {
        return;
    }
    telemetry.on_morph_rollback(target, morph_policy_id(policy), reason);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol_payload::DEFAULT_FAKE_TLS;

    use super::super::config::{DesyncGroup, EntropyMode, QuicFakeProfile, TcpChainStep, TcpChainStepKind};
    use super::super::desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};

    fn policy() -> ProxyMorphPolicy {
        ProxyMorphPolicy {
            id: "balanced".to_string(),
            first_flight_size_min: 320,
            first_flight_size_max: 640,
            padding_envelope_min: 16,
            padding_envelope_max: 64,
            entropy_target_permil: 3400,
            tcp_burst_cadence_ms: vec![0, 12, 24],
            tls_burst_cadence_ms: vec![0, 8],
            quic_burst_profile: "compat_burst".to_string(),
            fake_packet_shape_profile: "compat_default".to_string(),
        }
    }

    #[test]
    fn tcp_morph_policy_updates_group_actions_and_cadence() {
        let policy = policy();
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, super::super::config::OffsetExpr::tls_host(0)),
            TcpChainStep::new(TcpChainStepKind::Fake, super::super::config::OffsetExpr::host(1)),
        ];
        let hints =
            AdaptivePlannerHints { tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide), ..Default::default() };

        let morphed = apply_tcp_morph_policy_to_group(Some(&policy), &group, DEFAULT_FAKE_TLS, hints);

        assert_eq!(morphed.actions.fake_tls_size, 640);
        assert_eq!(morphed.actions.entropy_mode, EntropyMode::Popcount);
        assert_eq!(morphed.actions.entropy_padding_target_permil, Some(3400));
        assert_eq!(morphed.actions.entropy_padding_max, 64);
        assert_eq!(morphed.actions.tcp_chain[0].inter_segment_delay_ms(), 0);
        assert_eq!(morphed.actions.tcp_chain[1].inter_segment_delay_ms(), 8);
    }

    #[test]
    fn udp_morph_policy_overrides_hint_profiles() {
        let policy = ProxyMorphPolicy {
            id: "balanced".to_string(),
            first_flight_size_min: 0,
            first_flight_size_max: 0,
            padding_envelope_min: 0,
            padding_envelope_max: 0,
            entropy_target_permil: 0,
            tcp_burst_cadence_ms: Vec::new(),
            tls_burst_cadence_ms: Vec::new(),
            quic_burst_profile: "realistic_burst".to_string(),
            fake_packet_shape_profile: "realistic_initial".to_string(),
        };

        let hints = apply_udp_morph_policy_to_hints(Some(&policy), AdaptivePlannerHints::default());

        assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Aggressive));
        assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
    }
}
