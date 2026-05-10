use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn yaml_step_type_resolves_to_registered_technique() {
    let yaml = "version: 1\nstrategies:\n  - id: fake_chain\n    steps:\n      - type: fake\n";
    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let registry = StrategyRegistry::with_builtin_techniques();
    let step = &config.strategies[0].steps[0];

    assert_eq!(step.kind.registry_id(), "fake");
    assert!(registry.get(step.kind.registry_id()).is_some());
}

#[test]
fn synack_yaml_step_types_resolve_to_registered_techniques() {
    let yaml = "version: 1\nstrategies:\n  - id: synack_chain\n    steps:\n      - type: synack\n        ttl: 5\n      - type: synack_split\n";
    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let registry = StrategyRegistry::with_builtin_techniques();

    for step in &config.strategies[0].steps {
        assert!(registry.get(step.kind.registry_id()).is_some(), "{} did not resolve", step.kind.registry_id());
    }
}

#[test]
fn all_registry_ids_parse_as_yaml_step_types() {
    let ids = [
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
        "ipv6_ext",
        "synack",
        "synack_split",
    ];
    let steps = ids.iter().map(|id| format!("      - type: {id}")).collect::<Vec<_>>().join("\n");
    let yaml = format!("version: 1\nstrategies:\n  - id: all_ids\n    steps:\n{steps}\n");
    let config = parse_yaml_str(&yaml, ".").expect("all registry IDs should parse as YAML type values");
    let registry = StrategyRegistry::with_builtin_techniques();

    let parsed_ids = config.strategies[0].steps.iter().map(|step| step.kind.registry_id()).collect::<Vec<_>>();
    assert_eq!(parsed_ids, ids);
    for id in parsed_ids {
        assert!(registry.get(id).is_some(), "{id} did not resolve");
    }
}
