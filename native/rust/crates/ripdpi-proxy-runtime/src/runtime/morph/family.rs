use ripdpi_proxy_config::ProxyMorphPolicy;
use ripdpi_proxy_runtime_adapter::desync_model::AdaptivePlannerHints;

pub(super) fn tcp_morph_hint_family(
    policy: Option<&ProxyMorphPolicy>,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::tcp_morph_hint_family(policy, payload, hints)
}

pub(super) fn udp_morph_hint_family(policy: Option<&ProxyMorphPolicy>, hints: AdaptivePlannerHints) -> Option<String> {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::udp_morph_hint_family(policy, hints)
}
