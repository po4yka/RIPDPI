#[cfg(test)]
pub(super) fn apply_udp_morph_policy_to_hints(
    policy: Option<&ripdpi_proxy_config::ProxyMorphPolicy>,
    hints: ripdpi_desync::AdaptivePlannerHints,
) -> ripdpi_desync::AdaptivePlannerHints {
    ripdpi_runtime_adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
}
