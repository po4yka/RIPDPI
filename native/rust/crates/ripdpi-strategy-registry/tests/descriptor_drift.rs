//! Drift tests for the strategy descriptor/factory platform.
//!
//! These pin the three sources that must agree: the `StepType` config parser,
//! the `StrategyStepDescriptor`s contributed to `STRATEGY_STEP_REGISTRATIONS`,
//! and the registry's resolution of a descriptor into a registered strategy.

use ripdpi_strategy_config::StepType;
use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn every_step_type_maps_to_exactly_one_descriptor() {
    for step in StepType::ALL {
        let id = step.registry_id();
        let matches = StrategyRegistry::step_descriptors().filter(|descriptor| descriptor.id == id).count();
        assert_eq!(matches, 1, "expected exactly one strategy step descriptor for StepType `{id}`, found {matches}");
    }
}

#[test]
fn step_descriptor_count_matches_the_known_step_types() {
    // Every descriptor is one known step kind and vice versa — no orphan
    // descriptor, no `StepType` variant without a descriptor.
    assert_eq!(StrategyRegistry::step_descriptors().count(), StepType::ALL.len());
}

#[test]
fn descriptor_ids_are_non_empty_and_unique() {
    let mut seen: Vec<&str> = Vec::new();
    for descriptor in StrategyRegistry::step_descriptors() {
        assert!(!descriptor.id.is_empty(), "a strategy step descriptor has an empty id");
        assert!(!seen.contains(&descriptor.id), "duplicate strategy step descriptor id: {}", descriptor.id);
        seen.push(descriptor.id);
    }
}

#[test]
fn descriptor_id_and_aliases_parse_back_to_the_descriptor() {
    // The descriptor's accepted-alias record must stay in lock-step with the
    // `StepType` config parser: every id/alias the descriptor advertises has to
    // resolve, through `from_wire`, to that same descriptor id.
    for descriptor in StrategyRegistry::step_descriptors() {
        assert_eq!(
            StepType::from_wire(descriptor.id).registry_id(),
            descriptor.id,
            "descriptor id `{}` does not round-trip through StepType::from_wire",
            descriptor.id,
        );
        for alias in descriptor.aliases {
            assert_eq!(
                StepType::from_wire(alias).registry_id(),
                descriptor.id,
                "descriptor `{}` advertises alias `{alias}` that does not parse back to it",
                descriptor.id,
            );
        }
    }
}

#[test]
fn every_descriptor_is_resolvable_by_the_registry() {
    for descriptor in StrategyRegistry::step_descriptors() {
        if descriptor.id == "lua" {
            // Lua is feature-gated and needs config (function + script_paths);
            // it is resolvable through `from_loaded_config`, not as a bare
            // built-in technique. The `lua` resolution path is exercised by
            // `config_materialization.rs`.
            continue;
        }

        let mut registry = StrategyRegistry::new();
        registry
            .register_builtin_technique(descriptor.id)
            .unwrap_or_else(|error| panic!("descriptor `{}` did not resolve: {error}", descriptor.id));

        let resolved = registry.get(descriptor.id).unwrap_or_else(|| {
            panic!("descriptor `{}` is missing from the registry after registration", descriptor.id)
        });
        assert_eq!(resolved.id, descriptor.id, "resolved strategy id drifted from descriptor `{}`", descriptor.id);
        assert_eq!(
            resolved.required_tier, descriptor.required_tier,
            "resolved tier drifted from descriptor `{}`",
            descriptor.id,
        );
        assert_eq!(
            resolved.required_capabilities, descriptor.required_capabilities,
            "resolved capabilities drifted from descriptor `{}`",
            descriptor.id,
        );
    }
}

#[test]
fn with_builtin_techniques_registers_every_non_lua_descriptor() {
    let registry = StrategyRegistry::with_builtin_techniques();
    for descriptor in StrategyRegistry::step_descriptors() {
        if descriptor.id == "lua" {
            continue;
        }
        assert!(
            registry.get(descriptor.id).is_some(),
            "with_builtin_techniques did not register descriptor `{}`",
            descriptor.id,
        );
    }
}

#[test]
fn linked_strategy_ids_match_the_non_lua_descriptors() {
    let mut linked: Vec<&str> = StrategyRegistry::linked_strategy_ids().collect();
    linked.sort_unstable();
    let mut descriptors: Vec<&str> =
        StrategyRegistry::step_descriptors().map(|descriptor| descriptor.id).filter(|id| *id != "lua").collect();
    descriptors.sort_unstable();
    assert_eq!(linked, descriptors, "linked step registrations drifted from the step descriptors");
}
