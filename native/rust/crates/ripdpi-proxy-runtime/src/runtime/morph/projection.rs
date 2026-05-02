use ripdpi_config::{DesyncGroup, EntropyMode, TcpChainStepKind};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile};
use ripdpi_proxy_config::ProxyMorphPolicy;
use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;

pub(super) fn apply_tcp_morph_policy_to_group(
    policy: Option<&ProxyMorphPolicy>,
    group: &DesyncGroup,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> DesyncGroup {
    let Some(policy) = policy else {
        return group.clone();
    };
    let mutations = TcpMorphMutations::from_policy(policy, payload, hints);
    mutations.apply_to(group)
}

struct TcpMorphMutations {
    entropy_target_permil: Option<u32>,
    entropy_padding_max: Option<u32>,
    fake_tls_size: Option<i32>,
    cadence: Vec<u32>,
}

impl TcpMorphMutations {
    fn from_policy(policy: &ProxyMorphPolicy, payload: &[u8], hints: AdaptivePlannerHints) -> Self {
        let entropy_target_permil =
            (policy.entropy_target_permil > 0).then(|| policy.entropy_target_permil.max(0) as u32);
        let entropy_padding_max = (policy.padding_envelope_max > 0).then(|| policy.padding_envelope_max.max(0) as u32);
        let fake_tls_size = (policy.first_flight_size_min > 0 || policy.first_flight_size_max > 0)
            .then(|| select_first_flight_size(policy, payload.len(), hints));
        let cadence = select_tcp_cadence(policy, is_tls_client_hello_payload(payload));
        Self { entropy_target_permil, entropy_padding_max, fake_tls_size, cadence }
    }

    fn apply_to(&self, group: &DesyncGroup) -> DesyncGroup {
        let mut morphed = group.clone();
        if let Some(target) = self.entropy_target_permil {
            morphed.actions.entropy_mode = EntropyMode::Popcount;
            morphed.actions.entropy_padding_target_permil = Some(target);
        }
        if let Some(max) = self.entropy_padding_max {
            morphed.actions.entropy_padding_max = max;
        }
        if let Some(size) = self.fake_tls_size {
            morphed.actions.fake_tls_size = size;
        }
        if !self.cadence.is_empty() {
            for (index, step) in
                morphed.actions.tcp_chain.iter_mut().filter(|step| step_supports_cadence(step.kind)).enumerate()
            {
                step.inter_segment_delay_ms = self.cadence[index % self.cadence.len()];
            }
        }
        morphed
    }
}

fn select_first_flight_size(policy: &ProxyMorphPolicy, payload_len: usize, hints: AdaptivePlannerHints) -> i32 {
    let min = policy.first_flight_size_min.max(0);
    let max = policy.first_flight_size_max.max(min);
    if max == 0 {
        return payload_len as i32;
    }
    let bucket = match hints.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Tight => min,
        AdaptiveTlsRandRecProfile::Wide => max,
        AdaptiveTlsRandRecProfile::Balanced => min + ((max - min) / 2),
    };
    let padding = select_padding_envelope(policy, hints);
    bucket.max((payload_len as i32).saturating_add(padding)).clamp(min, max)
}

fn select_padding_envelope(policy: &ProxyMorphPolicy, hints: AdaptivePlannerHints) -> i32 {
    let min = policy.padding_envelope_min.max(0);
    let max = policy.padding_envelope_max.max(min);
    if max == min {
        return max;
    }
    match hints.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Tight => min,
        AdaptiveTlsRandRecProfile::Wide => max,
        AdaptiveTlsRandRecProfile::Balanced => min + ((max - min) / 2),
    }
}

fn select_tcp_cadence(policy: &ProxyMorphPolicy, is_tls: bool) -> Vec<u32> {
    let source = if is_tls && !policy.tls_burst_cadence_ms.is_empty() {
        &policy.tls_burst_cadence_ms
    } else {
        &policy.tcp_burst_cadence_ms
    };
    source.iter().map(|value| (*value).max(0) as u32).collect()
}

fn step_supports_cadence(kind: TcpChainStepKind) -> bool {
    matches!(
        kind,
        TcpChainStepKind::Split
            | TcpChainStepKind::Disorder
            | TcpChainStepKind::MultiDisorder
            | TcpChainStepKind::Fake
            | TcpChainStepKind::FakeSplit
            | TcpChainStepKind::FakeDisorder
            | TcpChainStepKind::HostFake
            | TcpChainStepKind::Oob
            | TcpChainStepKind::Disoob
            | TcpChainStepKind::TlsRec
            | TcpChainStepKind::TlsRandRec
    )
}
