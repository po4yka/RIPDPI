use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn built_in_technique_ids_resolve() {
    let registry = StrategyRegistry::with_builtin_techniques();
    for id in [
        "split",
        "disorder",
        "fake",
        "oob",
        "fake_rst",
        "seq_overlap",
        "ip_frag",
        "multi_disorder",
        "tls_rec",
        "tls_rand_rec",
        "udplen",
        "http_domcase",
        "http_hostcase",
        "http_methodeol",
        "http_unixeol",
        "wsize",
        "wssize",
        "synack",
        "synack_split",
    ] {
        assert!(registry.get(id).is_some(), "{id} did not resolve");
    }
}
