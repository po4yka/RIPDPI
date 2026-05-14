//! Native enforcement of the strategy-pack "fixed-config protocol" hint.
//!
//! Mirrors the Kotlin `StrategyPackCatalog.validateCandidateArm` semantics:
//! a candidate arm that mutates any param of a fixed-config protocol (e.g. an
//! AmneziaWG profile's `Jc`) must be rejected, while a selection-only arm
//! (empty `mutated_params`) is always allowed.

use ripdpi_strategy_registry::{CandidateArm, FixedConfigProtocols, StrategyRegistry};

fn awg_arm(id: &str, mutated: &[&str]) -> CandidateArm {
    CandidateArm {
        id: id.to_owned(),
        protocol: "amneziawg".to_owned(),
        mutated_params: mutated.iter().map(|param| (*param).to_owned()).collect(),
    }
}

#[test]
fn default_fixed_config_set_includes_amneziawg() {
    let protocols = FixedConfigProtocols::default();
    assert!(protocols.is_fixed_config("amneziawg"));
}

#[test]
fn awg_arm_mutating_jc_is_rejected() {
    let protocols = FixedConfigProtocols::default();
    let arm = awg_arm("awg-jc-mutation", &["Jc"]);

    let outcome = protocols.validate_candidate_arm(&arm);

    let violation = outcome.expect_err("mutating Jc on an AWG profile must be rejected");
    assert!(!violation.to_string().is_empty());
    assert!(violation.to_string().contains("awg-jc-mutation"));
}

#[test]
fn awg_arm_mutating_any_param_family_is_rejected() {
    let protocols = FixedConfigProtocols::default();
    let families = ["Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5"];

    for family in families {
        let arm = awg_arm(&format!("awg-mutation-{family}"), &[family]);
        assert!(
            protocols.validate_candidate_arm(&arm).is_err(),
            "mutating {family} on an AWG profile must be rejected",
        );
    }
}

#[test]
fn awg_arm_with_empty_mutated_params_is_allowed() {
    let protocols = FixedConfigProtocols::default();
    let arm = awg_arm("awg-profile-selection", &[]);

    assert!(protocols.validate_candidate_arm(&arm).is_ok());
}

#[test]
fn non_fixed_config_protocol_may_mutate_params_freely() {
    let protocols = FixedConfigProtocols::default();
    let arm = CandidateArm {
        id: "tls-record-split".to_owned(),
        protocol: "vless".to_owned(),
        mutated_params: vec!["tlsRecordSplit".to_owned(), "fragmentSize".to_owned()],
    };

    assert!(protocols.validate_candidate_arm(&arm).is_ok());
}

#[test]
fn fixed_config_match_is_case_and_trim_insensitive() {
    let protocols = FixedConfigProtocols::default();

    assert!(protocols.is_fixed_config("AmneziaWG"));
    assert!(protocols.is_fixed_config(" amneziawg "));

    let arm = CandidateArm {
        id: "awg-cased".to_owned(),
        protocol: " AmneziaWG ".to_owned(),
        mutated_params: vec!["Jc".to_owned()],
    };
    assert!(protocols.validate_candidate_arm(&arm).is_err());
}

#[test]
fn filter_candidate_arms_drops_only_violating_arms_preserving_order() {
    let protocols = FixedConfigProtocols::default();
    let arms = vec![
        CandidateArm {
            id: "vless-frag".to_owned(),
            protocol: "vless".to_owned(),
            mutated_params: vec!["fragmentSize".to_owned()],
        },
        awg_arm("awg-jc-mutation", &["Jc"]),
        awg_arm("awg-profile-selection", &[]),
        CandidateArm {
            id: "tls-split".to_owned(),
            protocol: "tls".to_owned(),
            mutated_params: vec!["recordSplit".to_owned()],
        },
        awg_arm("awg-s1-mutation", &["S1"]),
    ];

    let kept = protocols.filter_candidate_arms(&arms);
    let kept_ids = kept.iter().map(|arm| arm.id.as_str()).collect::<Vec<_>>();

    assert_eq!(kept_ids, ["vless-frag", "awg-profile-selection", "tls-split"]);
}

#[test]
fn registry_exposes_candidate_arm_enforcement() {
    let registry = StrategyRegistry::new();
    let rejected = awg_arm("awg-jc-mutation", &["Jc"]);
    let allowed = awg_arm("awg-profile-selection", &[]);

    assert!(registry.validate_candidate_arm(&rejected).is_err());
    assert!(registry.validate_candidate_arm(&allowed).is_ok());

    let kept = registry.filter_candidate_arms(&[rejected, allowed]);
    assert_eq!(kept.len(), 1);
    assert_eq!(kept[0].id, "awg-profile-selection");
}
