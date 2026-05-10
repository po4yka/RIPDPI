use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn linked_strategy_factories_include_concrete_strategy_crates() {
    let ids = StrategyRegistry::linked_strategy_ids().collect::<Vec<_>>();

    for id in
        ["http_domcase", "http_hostcase", "http_methodeol", "http_unixeol", "ipv6_ext", "udplen", "wsize", "wssize"]
    {
        assert!(ids.contains(&id), "{id} was not link-registered; linked IDs: {ids:?}");
    }
}

#[test]
fn linked_descriptors_include_parameterized_tcp_desync_strategy() {
    let ids = StrategyRegistry::linked_descriptor_ids().collect::<Vec<_>>();
    assert!(ids.contains(&"tcp_desync"), "tcp_desync descriptor was not link-registered; linked IDs: {ids:?}");

    let descriptor = StrategyRegistry::linked_descriptors()
        .find(|descriptor| descriptor.id == "tcp_desync")
        .expect("tcp_desync descriptor");
    assert_eq!(descriptor.label, "TCP desync planner");
    assert!(!descriptor.supported_protocols.is_empty());
}
