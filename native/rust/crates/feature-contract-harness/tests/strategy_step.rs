//! Cross-layer contract for strategy steps.
//!
//! Each manifest's `wireId` must round-trip through `StepType::from_wire` ↔
//! `registry_id`, and must resolve to a descriptor contributed to the
//! `STRATEGY_STEP_REGISTRATIONS` linkme slice via `StrategyRegistry`. Together
//! with the layer-marker check this catches both half-edits (registration
//! added but no StepType variant) and registry mis-wiring (variant added but
//! no linkme contribution).

use feature_contract_harness::{assert_layer_markers, load_family_manifests, locate_repo_root};
use ripdpi_strategy_config::StepType;
use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn strategy_step_manifests_pin_their_cross_layer_markers() {
    let repo_root = locate_repo_root();
    for (_path, manifest) in load_family_manifests("strategy_step") {
        assert_layer_markers(&repo_root, &manifest);
    }
}

#[test]
fn strategy_step_manifest_wire_ids_round_trip_through_step_type() {
    for (path, manifest) in load_family_manifests("strategy_step") {
        let parsed = StepType::from_wire(&manifest.wire_id);
        assert!(
            !matches!(parsed, StepType::Unknown(_)),
            "strategy_step manifest {} declares wireId `{}` that StepType::from_wire returned as Unknown.\n\
             Add the variant to ripdpi-strategy-config (variant, ALL list, registry_id(), from_wire()) — \
             see manifests/strategy_step/{}.json checklist for the full set of edits.",
            path.display(),
            manifest.wire_id,
            manifest.name,
        );
        assert_eq!(
            parsed.registry_id(),
            manifest.wire_id,
            "wireId `{}` parses to a different StepType (registry_id `{}`) — the manifest, the StepType variant, \
             and the descriptor id must all be the same canonical string",
            manifest.wire_id,
            parsed.registry_id(),
        );
    }
}

#[test]
fn strategy_step_manifest_wire_ids_resolve_in_the_registry() {
    for (path, manifest) in load_family_manifests("strategy_step") {
        let matches = StrategyRegistry::step_descriptors()
            .filter(|descriptor| descriptor.id == manifest.wire_id.as_str())
            .count();
        assert_eq!(
            matches,
            1,
            "strategy_step manifest {} (wireId `{}`) expected exactly one StrategyStepDescriptor in \
             STRATEGY_STEP_REGISTRATIONS — found {matches}.\n\
             Likely cause: the strategy crate is missing `extern crate ripdpi_strategy_<name> as _;` in \
             ripdpi-strategy-registry/src/lib.rs, so linkme is not picking up the slice entry.",
            path.display(),
            manifest.wire_id,
        );
    }
}
