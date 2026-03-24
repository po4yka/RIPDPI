mod strategy;
#[cfg(test)]
mod diagnosis;

pub(crate) use strategy::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, filter_quic_candidates_for_failure,
    interleave_candidate_families, next_candidate_index, reorder_tcp_candidates_for_failure,
};

#[cfg(test)]
pub(crate) use diagnosis::{
    classify_connectivity_diagnoses, classify_strategy_probe_baseline_results, classify_transport_failure_text,
    failure_detail_value, strategy_probe_failure_weight,
};

use std::collections::BTreeMap;

pub(crate) fn pack_versions_from_refs(pack_refs: &[String]) -> BTreeMap<String, u32> {
    let mut versions = BTreeMap::new();
    for pack_ref in pack_refs {
        let trimmed = pack_ref.trim();
        if trimmed.is_empty() {
            continue;
        }
        let parsed = trimmed
            .rsplit_once('@')
            .or_else(|| trimmed.rsplit_once(':'))
            .and_then(|(pack_id, version)| version.parse::<u32>().ok().map(|parsed| (pack_id, parsed)));
        match parsed {
            Some((pack_id, version)) if !pack_id.trim().is_empty() => {
                versions.insert(pack_id.trim().to_string(), version.max(1));
            }
            _ => {
                versions.insert(trimmed.to_string(), 1);
            }
        }
    }
    versions
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pack_versions_from_refs_extracts_versions() {
        let versions = pack_versions_from_refs(&[
            "ru-independent-media@3".to_string(),
            "ru-messaging:2".to_string(),
            "ru-control".to_string(),
        ]);
        assert_eq!(versions.get("ru-independent-media"), Some(&3));
        assert_eq!(versions.get("ru-messaging"), Some(&2));
        assert_eq!(versions.get("ru-control"), Some(&1));
    }
}
