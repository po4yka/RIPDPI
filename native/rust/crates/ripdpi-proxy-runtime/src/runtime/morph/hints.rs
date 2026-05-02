use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::ProxyMorphPolicy;

pub(super) fn apply_tcp_morph_policy_to_hints(
    policy: Option<&ProxyMorphPolicy>,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    ripdpi_runtime_adaptive::morph_policy::apply_tcp_morph_policy_to_hints(policy, hints)
}

pub(super) fn apply_udp_morph_policy_to_hints(
    policy: Option<&ProxyMorphPolicy>,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    ripdpi_runtime_adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
}
