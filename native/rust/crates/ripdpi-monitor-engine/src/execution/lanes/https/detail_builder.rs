use crate::candidates::StrategyCandidateSpec;
use crate::connectivity::append_tls_attempt_details;
use crate::tls::{
    TlsClientProfile, TlsObservation, append_tls_comparison_details, planned_tls_template_metadata,
    planned_tls_template_profile, preferred_tls_observation,
};
use crate::transport::TransportConfig;
use crate::types::ProbeDetail;

use super::super::support::candidate_probe_details;
use super::observation_collection::HttpsObservationCollection;

pub(super) fn build_https_probe_details(
    candidate: &StrategyCandidateSpec,
    observations: &HttpsObservationCollection,
    outcome: &str,
    transport: &TransportConfig,
) -> Vec<ProbeDetail> {
    let tls13 = &observations.tls13;
    let tls12 = &observations.tls12;
    let tls_ech = &observations.tls_ech;
    let (selected_prefix, selected) = report_attempt(observations, outcome);
    let tls13_template = planned_tls_template_metadata(TlsClientProfile::Tls13Only);
    let tls12_template = planned_tls_template_metadata(TlsClientProfile::Tls12Only);
    let tls_ech_template = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);

    let mut details = candidate_probe_details(candidate, "HTTPS", observations.latency_ms);
    for (prefix, attempt) in [("tls13", tls13), ("tls12", tls12), ("tlsEch", tls_ech)] {
        append_tls_attempt_details(&mut details, prefix, attempt, transport);
    }
    details.extend([
        ProbeDetail {
            key: "tls13TemplateProfileId".to_string(),
            value: planned_tls_template_profile(TlsClientProfile::Tls13Only).to_string(),
        },
        ProbeDetail {
            key: "tls12TemplateProfileId".to_string(),
            value: planned_tls_template_profile(TlsClientProfile::Tls12Only).to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateProfileId".to_string(),
            value: planned_tls_template_profile(TlsClientProfile::Tls13WithEch).to_string(),
        },
        ProbeDetail {
            key: "tls13TemplateBrowserTrack".to_string(),
            value: tls13_template.parity_targets.browser_track.to_string(),
        },
        ProbeDetail {
            key: "tls12TemplateBrowserTrack".to_string(),
            value: tls12_template.parity_targets.browser_track.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateBrowserTrack".to_string(),
            value: tls_ech_template.parity_targets.browser_track.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateGreaseStyle".to_string(),
            value: tls_ech_template.template.grease_style.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateBootstrapPolicy".to_string(),
            value: tls_ech_template.template.ech_bootstrap_policy.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateBootstrapResolverId".to_string(),
            value: tls_ech_template.template.ech_bootstrap_resolver_id.unwrap_or("none").to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateOuterExtensionPolicy".to_string(),
            value: tls_ech_template.template.ech_outer_extension_policy.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateAlpn".to_string(),
            value: tls_ech_template.template.alpn_template.to_string(),
        },
        ProbeDetail { key: "tlsReportedAttempt".to_string(), value: selected_prefix.to_string() },
        ProbeDetail {
            key: "tlsError".to_string(),
            value: selected.error.clone().unwrap_or_else(|| "none".to_string()),
        },
    ]);
    push_ech_observation_details(&mut details, tls_ech);
    if let Some(addr) = selected.connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    if let Some(provider) = &selected.cdn_provider {
        details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider.clone() });
    }
    details.push(ProbeDetail {
        key: "tlsEchTemplateCapable".to_string(),
        value: tls_ech_template.template.ech_capable.to_string(),
    });
    append_tls_comparison_details(&mut details, outcome, tls13, tls12, tls_ech);
    details
}

fn report_attempt<'a>(
    observations: &'a HttpsObservationCollection,
    outcome: &str,
) -> (&'static str, &'a TlsObservation) {
    if outcome == "tls_ech_only" {
        return ("tlsEch", &observations.tls_ech);
    }
    let tls13 = &observations.tls13;
    let tls12 = &observations.tls12;
    let preferred = preferred_tls_observation(tls13, tls12);
    if !std::ptr::eq(preferred, tls13) {
        return ("tls12", tls12);
    }
    if tls13.status != "tls_ok" && !tls13.certificate_anomaly {
        if (tls13.tls_peer_response_after_client_hello != Some(true)
            && tls12.tls_peer_response_after_client_hello == Some(true))
            || (tls13.transport_open_ms.is_none() && tls12.transport_open_ms.is_some())
        {
            return ("tls12", tls12);
        }
        if tls13.transport_open_ms.is_none() && observations.tls_ech.transport_open_ms.is_some() {
            return ("tlsEch", &observations.tls_ech);
        }
    }
    ("tls13", tls13)
}

fn push_ech_observation_details(details: &mut Vec<ProbeDetail>, tls_ech: &TlsObservation) {
    if let Some(policy) = tls_ech.ech_bootstrap_policy.clone() {
        details.push(ProbeDetail { key: "tlsEchBootstrapPolicy".to_string(), value: policy });
    }
    if let Some(resolver_id) = tls_ech.ech_bootstrap_resolver_id.clone() {
        details.push(ProbeDetail { key: "tlsEchBootstrapResolverId".to_string(), value: resolver_id });
    }
    if let Some(policy) = tls_ech.ech_outer_extension_policy.clone() {
        details.push(ProbeDetail { key: "tlsEchOuterExtensionPolicy".to_string(), value: policy });
    }
    if let Some(plan) = tls_ech.ech_first_flight_plan.clone() {
        details.push(ProbeDetail { key: "tlsEchFirstFlightPlan".to_string(), value: plan });
    }
}
