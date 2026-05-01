use std::collections::{BTreeMap, BTreeSet};

use crate::types::ProbeDetail;

pub(crate) struct TriggerFuzzOutcome {
    pub(crate) id: &'static str,
    pub(crate) field: &'static str,
    pub(crate) outcome: String,
    pub(crate) detail: String,
}

pub(crate) fn append_trigger_fuzzing_summary(
    details: &mut Vec<ProbeDetail>,
    prefix: &str,
    baseline: &str,
    outcomes: &[TriggerFuzzOutcome],
) {
    if outcomes.is_empty() {
        return;
    }

    let mut grouped = BTreeMap::<&str, Vec<&TriggerFuzzOutcome>>::new();
    for outcome in outcomes {
        grouped.entry(outcome.field).or_default().push(outcome);
    }

    let changed_fields = grouped
        .iter()
        .filter_map(|(field, entries)| entries.iter().any(|entry| entry.outcome != baseline).then_some(*field))
        .collect::<BTreeSet<_>>();

    details.push(ProbeDetail { key: format!("{prefix}Baseline"), value: baseline.to_string() });
    details.push(ProbeDetail { key: format!("{prefix}VariantCount"), value: outcomes.len().to_string() });
    details.push(ProbeDetail {
        key: format!("{prefix}Outcomes"),
        value: outcomes
            .iter()
            .map(|outcome| {
                format!("{}={}:{}", outcome.id, outcome.outcome, sanitize_detail_value(outcome.detail.as_str()))
            })
            .collect::<Vec<_>>()
            .join("|"),
    });
    details.push(ProbeDetail {
        key: format!("{prefix}FieldOutcomes"),
        value: grouped
            .iter()
            .map(|(field, entries)| {
                format!(
                    "{field}={}",
                    entries.iter().map(|entry| format!("{}:{}", entry.id, entry.outcome)).collect::<Vec<_>>().join(",")
                )
            })
            .collect::<Vec<_>>()
            .join(";"),
    });
    details.push(ProbeDetail {
        key: format!("{prefix}ChangedFields"),
        value: if changed_fields.is_empty() {
            "none".to_string()
        } else {
            changed_fields.iter().copied().collect::<Vec<_>>().join("|")
        },
    });
    details.push(ProbeDetail { key: format!("{prefix}ChangedCount"), value: changed_fields.len().to_string() });
}

fn sanitize_detail_value(value: &str) -> String {
    value.replace(['|', ';', ','], "/").replace(['\n', '\r'], " ")
}
