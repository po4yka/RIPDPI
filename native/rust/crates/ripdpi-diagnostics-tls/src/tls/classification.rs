use ripdpi_diagnostics_contracts::types::ProbeDetail;
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

/// Record the comparison scope and first measured handshake failure without mixing attempts.
pub fn append_tls_comparison_details(
    details: &mut Vec<ProbeDetail>,
    outcome: &str,
    tls13: &TlsObservation,
    tls12: &TlsObservation,
    tls_ech: &TlsObservation,
) {
    if outcome == "tls_version_split" {
        details.push(ProbeDetail {
            key: "tlsVersionComparisonScope".to_string(),
            value: tls_version_comparison_scope(tls13, tls12).to_string(),
        });
    }

    let attempts = [("tls13", tls13), ("tls12", tls12), ("tls_ech", tls_ech)];
    let Some((profile, failed)) =
        attempts.into_iter().find(|(_, attempt)| attempt.failure_stage.is_some() && attempt.tls_handshake_ms.is_some())
    else {
        return;
    };

    details.push(ProbeDetail { key: "tlsFailureProfile".to_string(), value: profile.to_string() });
    if let Some(server_hello) = failed.tls_server_hello_received {
        details.push(ProbeDetail { key: "tlsServerHelloReceived".to_string(), value: server_hello.to_string() });
    }
    if let Some(code) = failed.tls_alert_code {
        details.push(ProbeDetail { key: "tlsAlertCode".to_string(), value: code.to_string() });
        details.push(ProbeDetail { key: "tlsAlertSource".to_string(), value: "unknown".to_string() });
    }
    if let Some(description) = &failed.tls_alert_description {
        details.push(ProbeDetail { key: "tlsAlertDescription".to_string(), value: description.clone() });
    }
    if let Some(ms) = failed.tls_handshake_ms {
        details.push(ProbeDetail { key: "tlsFailureHandshakeMs".to_string(), value: ms.to_string() });
    }
}

/// Whether the TLS versions were probed at the same known destination.
fn tls_version_comparison_scope(tls13: &TlsObservation, tls12: &TlsObservation) -> &'static str {
    match (tls13.connected_addr, tls12.connected_addr) {
        (Some(tls13_addr), Some(tls12_addr)) if tls13_addr == tls12_addr => "same_peer",
        (Some(_), Some(_)) => "mixed_peers",
        _ => "unknown_peer",
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
