use ripdpi_config::DesyncGroup;
use ripdpi_proxy_config::ProxyMorphPolicy;
use ripdpi_proxy_runtime_adapter::desync_model::AdaptivePlannerHints;

pub(super) fn apply_tcp_morph_policy_to_group(
    policy: Option<&ProxyMorphPolicy>,
    group: &DesyncGroup,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> DesyncGroup {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::apply_tcp_morph_policy_to_group(
        policy, group, payload, hints,
    )
}
