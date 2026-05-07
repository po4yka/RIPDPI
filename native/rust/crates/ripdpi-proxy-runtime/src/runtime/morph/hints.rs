#[cfg(test)]
pub(super) fn apply_udp_morph_policy_to_hints(
    policy: Option<&ripdpi_proxy_runtime_adapter::proxy_config::ProxyMorphPolicy>,
    hints: ripdpi_proxy_runtime_adapter::desync_model::AdaptivePlannerHints,
) -> ripdpi_proxy_runtime_adapter::desync_model::AdaptivePlannerHints {
    ripdpi_runtime_decision_ports::adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
}
