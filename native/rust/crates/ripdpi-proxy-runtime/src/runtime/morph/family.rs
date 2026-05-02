use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::ProxyMorphPolicy;

pub(super) fn tcp_morph_hint_family(
    policy: Option<&ProxyMorphPolicy>,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_adaptive::morph_policy::tcp_morph_hint_family(policy, payload, hints)
}

pub(super) fn udp_morph_hint_family(policy: Option<&ProxyMorphPolicy>, hints: AdaptivePlannerHints) -> Option<String> {
    ripdpi_runtime_adaptive::morph_policy::udp_morph_hint_family(policy, hints)
}
