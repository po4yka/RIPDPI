use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;
use rustls::client::EchStatus;

use super::types::TlsObservation;

pub fn tls_version_label(version: Option<rustls::ProtocolVersion>) -> Option<String> {
    version.map(|value| match value {
        rustls::ProtocolVersion::TLSv1_2 => "TLS1.2".to_string(),
        rustls::ProtocolVersion::TLSv1_3 => "TLS1.3".to_string(),
        other => format!("{other:?}"),
    })
}

pub fn ech_status_label(status: EchStatus) -> String {
    match status {
        EchStatus::NotOffered => "not_offered".to_string(),
        EchStatus::Grease => "grease".to_string(),
        EchStatus::Offered => "offered".to_string(),
        EchStatus::Accepted => "accepted".to_string(),
        EchStatus::Rejected => "rejected".to_string(),
    }
}
pub fn classify_tls_signal(tls13: &TlsObservation, tls12: &TlsObservation) -> &'static str {
    if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid"
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_consistent"
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split_low_confidence"
    } else {
        "tls_unavailable"
    }
}

pub fn preferred_tls_observation<'a>(tls13: &'a TlsObservation, tls12: &'a TlsObservation) -> &'a TlsObservation {
    if tls13.certificate_anomaly {
        tls13
    } else if tls12.certificate_anomaly {
        tls12
    } else if tls13.status == "tls_ok" {
        tls13
    } else if tls12.status == "tls_ok" {
        tls12
    } else {
        tls13
    }
}

pub(super) fn first_flight_plan_label(plan: &TlsTemplateFirstFlightPlan) -> String {
    format!(
        "{}|{}|resolver={}|outer={}|ech_present={}",
        plan.record_choreography.as_str(),
        plan.ech_bootstrap_policy,
        plan.ech_bootstrap_resolver_id.unwrap_or("none"),
        plan.ech_outer_extension_policy,
        plan.ech_present_in_input
    )
}
