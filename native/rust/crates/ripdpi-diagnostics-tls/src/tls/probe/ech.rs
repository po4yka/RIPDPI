use rustls::client::EchStatus;

use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;

use super::super::classification::{ech_status_label, first_flight_plan_label};
use super::super::config::{ECH_CONFIG_UNAVAILABLE_ERROR, planned_tls_template_metadata};
use super::super::types::TlsClientProfile;

pub(crate) struct EchOutcome {
    pub(crate) status: String,
    pub(crate) error: Option<String>,
    pub(crate) resolution_detail: Option<String>,
    pub(crate) bootstrap_policy: Option<String>,
    pub(crate) bootstrap_resolver_id: Option<String>,
    pub(crate) outer_extension_policy: Option<String>,
    pub(crate) first_flight_plan: Option<String>,
}

pub(crate) fn from_accepted_status(ech_status: EchStatus, plan: Option<&TlsTemplateFirstFlightPlan>) -> EchOutcome {
    let metadata = plan.map(EchMetadata::from);
    let accepted = matches!(ech_status, EchStatus::Accepted);

    EchOutcome {
        status: if accepted { "tls_ok" } else { "tls_handshake_failed" }.to_string(),
        error: if accepted { None } else { Some(format!("ech_{}", ech_status_label(ech_status))) },
        resolution_detail: Some("ech_config_available".to_string()),
        bootstrap_policy: metadata.as_ref().map(|value| value.bootstrap_policy.clone()),
        bootstrap_resolver_id: metadata.as_ref().and_then(|value| value.bootstrap_resolver_id.clone()),
        outer_extension_policy: metadata.as_ref().map(|value| value.outer_extension_policy.clone()),
        first_flight_plan: plan.map(first_flight_plan_label),
    }
}

pub(crate) fn from_resolution_error(err: &str, profile: TlsClientProfile) -> Option<EchOutcome> {
    if !matches!(profile, TlsClientProfile::Tls13WithEch) {
        return None;
    }
    if err != ECH_CONFIG_UNAVAILABLE_ERROR && !err.starts_with("ech_resolution_failed:") {
        return None;
    }

    let metadata = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
    let resolution_detail =
        if err == ECH_CONFIG_UNAVAILABLE_ERROR { "ech_not_published".to_string() } else { err.to_string() };

    Some(EchOutcome {
        status: "not_run".to_string(),
        error: Some(err.to_string()),
        resolution_detail: Some(resolution_detail),
        bootstrap_policy: Some(metadata.template.ech_bootstrap_policy.to_string()),
        bootstrap_resolver_id: metadata.template.ech_bootstrap_resolver_id.map(ToString::to_string),
        outer_extension_policy: Some(metadata.template.ech_outer_extension_policy.to_string()),
        first_flight_plan: None,
    })
}

struct EchMetadata {
    bootstrap_policy: String,
    bootstrap_resolver_id: Option<String>,
    outer_extension_policy: String,
}

impl From<&TlsTemplateFirstFlightPlan> for EchMetadata {
    fn from(plan: &TlsTemplateFirstFlightPlan) -> Self {
        Self {
            bootstrap_policy: plan.ech_bootstrap_policy.to_string(),
            bootstrap_resolver_id: plan.ech_bootstrap_resolver_id.map(ToString::to_string),
            outer_extension_policy: plan.ech_outer_extension_policy.to_string(),
        }
    }
}
