use crate::candidates::StrategyCandidateSpec;
use crate::tls::{
    TlsClientProfile, TlsObservation, append_tls_comparison_details, planned_tls_template_metadata,
    planned_tls_template_profile,
};
use crate::types::ProbeDetail;

use super::super::support::candidate_probe_details;
use super::observation_collection::{HttpsObservationCollection, SelectedTlsFields};
use super::outcome_classification::https_tls_error_detail;

pub(super) fn build_https_probe_details(
    candidate: &StrategyCandidateSpec,
    observations: &HttpsObservationCollection,
    outcome: &str,
) -> Vec<ProbeDetail> {
    let tls13 = &observations.tls13;
    let tls12 = &observations.tls12;
    let tls_ech = &observations.tls_ech;
    let selected = select_tls_fields(tls13, tls12, tls_ech);
    let tls13_template = planned_tls_template_metadata(TlsClientProfile::Tls13Only);
    let tls12_template = planned_tls_template_metadata(TlsClientProfile::Tls12Only);
    let tls_ech_template = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
    let tls_ech_error = tls_ech.error.clone().unwrap_or_else(|| "none".to_string());
    let tls_ech_resolution_detail = tls_ech.ech_resolution_detail.clone().unwrap_or_else(|| "none".to_string());
    let tls_error = https_tls_error_detail(outcome, tls13, tls12, tls_ech);

    let mut details = candidate_probe_details(candidate, "HTTPS", observations.latency_ms);
    details.extend([
        ProbeDetail { key: "tls13Status".to_string(), value: tls13.status.clone() },
        ProbeDetail { key: "tls12Status".to_string(), value: tls12.status.clone() },
        ProbeDetail { key: "tlsEchStatus".to_string(), value: tls_ech.status.clone() },
        ProbeDetail { key: "tls13FailureStage".to_string(), value: failure_stage_label(tls13) },
        ProbeDetail { key: "tls12FailureStage".to_string(), value: failure_stage_label(tls12) },
        ProbeDetail { key: "tlsEchFailureStage".to_string(), value: failure_stage_label(tls_ech) },
        ProbeDetail { key: "tls13FailureDurationMs".to_string(), value: failure_duration_label(tls13) },
        ProbeDetail { key: "tls12FailureDurationMs".to_string(), value: failure_duration_label(tls12) },
        ProbeDetail { key: "tlsEchFailureDurationMs".to_string(), value: failure_duration_label(tls_ech) },
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
        ProbeDetail {
            key: "tlsEchVersion".to_string(),
            value: tls_ech.version.clone().unwrap_or_else(|| "unknown".to_string()),
        },
        ProbeDetail { key: "tlsEchError".to_string(), value: tls_ech_error },
        ProbeDetail { key: "tlsEchResolutionDetail".to_string(), value: tls_ech_resolution_detail },
        ProbeDetail { key: "tlsError".to_string(), value: tls_error },
    ]);
    push_ech_observation_details(&mut details, tls_ech);
    push_selected_endpoint_details(&mut details, &selected);
    push_ech_capability_details(&mut details, observations, outcome, tls_ech_template.template.ech_capable);
    push_selected_tls_details(&mut details, &selected, observations.socket_leg);
    append_tls_comparison_details(&mut details, outcome, tls13, tls12, tls_ech);
    details
}

fn failure_stage_label(observation: &TlsObservation) -> String {
    observation.failure_stage.map_or_else(|| "none".to_string(), |stage| stage.as_str().to_string())
}

fn failure_duration_label(observation: &TlsObservation) -> String {
    observation.failure_duration_ms.map_or_else(|| "none".to_string(), |duration| duration.to_string())
}

fn select_tls_fields(tls13: &TlsObservation, tls12: &TlsObservation, tls_ech: &TlsObservation) -> SelectedTlsFields {
    let attempts = [tls13, tls12, tls_ech];
    let preferred = attempts
        .into_iter()
        .find(|attempt| attempt.status == "tls_ok")
        .or_else(|| attempts.into_iter().find(|attempt| attempt.transport_open_ms.is_some()))
        .unwrap_or(tls13);

    SelectedTlsFields {
        transport_open_ms: preferred.transport_open_ms,
        tls_handshake_ms: preferred.tls_handshake_ms,
        cert_chain_length: preferred.cert_chain_length,
        cert_issuer: preferred.cert_issuer.clone(),
        local_socket_ttl: preferred.local_socket_ttl,
        ja3_fingerprint: preferred.ja3_fingerprint.clone(),
        tls_negotiated_version: preferred.version.clone(),
        connected_addr: preferred.connected_addr,
        cdn_provider: preferred.cdn_provider.clone(),
    }
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

fn push_ech_capability_details(
    details: &mut Vec<ProbeDetail>,
    observations: &HttpsObservationCollection,
    outcome: &str,
    template_ech_capable: bool,
) {
    details.push(ProbeDetail {
        key: "echCapable".to_string(),
        value: (outcome == "tls_ech_only"
            || observations.tls_ech.ech_resolution_detail.as_deref() == Some("ech_config_available"))
        .to_string(),
    });
    details.push(ProbeDetail { key: "tlsEchTemplateCapable".to_string(), value: template_ech_capable.to_string() });
}

fn push_selected_endpoint_details(details: &mut Vec<ProbeDetail>, selected: &SelectedTlsFields) {
    if let Some(addr) = selected.connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    if let Some(provider) = selected.cdn_provider.clone() {
        details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider });
    }
}

fn push_selected_tls_details(details: &mut Vec<ProbeDetail>, selected: &SelectedTlsFields, socket_leg: &str) {
    if let Some(ms) = selected.transport_open_ms {
        details.push(ProbeDetail { key: "transportOpenMs".to_string(), value: ms.to_string() });
    }
    if let Some(ms) = selected.tls_handshake_ms {
        details.push(ProbeDetail { key: "tlsHandshakeMs".to_string(), value: ms.to_string() });
    }
    if let Some(len) = selected.cert_chain_length {
        details.push(ProbeDetail { key: "tlsCertChainLength".to_string(), value: len.to_string() });
    }
    if let Some(issuer) = selected.cert_issuer.clone() {
        details.push(ProbeDetail { key: "tlsCertIssuer".to_string(), value: issuer });
    }
    if let Some(ttl) = selected.local_socket_ttl {
        details.push(ProbeDetail { key: "localSocketTtl".to_string(), value: ttl.to_string() });
        details.push(ProbeDetail { key: "localSocketTtlLeg".to_string(), value: socket_leg.to_string() });
    }
    if let Some(ja3) = selected.ja3_fingerprint.clone() {
        details.push(ProbeDetail { key: "ja3Fingerprint".to_string(), value: ja3 });
    }
    if let Some(version) = selected.tls_negotiated_version.clone() {
        details.push(ProbeDetail { key: "tlsNegotiatedVersion".to_string(), value: version });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outbound_socket_ttl_is_not_labeled_as_server_observation() {
        let selected = SelectedTlsFields {
            transport_open_ms: None,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            local_socket_ttl: Some(64),
            ja3_fingerprint: None,
            tls_negotiated_version: None,
            connected_addr: None,
            cdn_provider: None,
        };
        let mut details = Vec::new();

        push_selected_tls_details(&mut details, &selected, "client_to_proxy");

        assert!(details.iter().any(|detail| detail.key == "localSocketTtl" && detail.value == "64"));
        assert!(details.iter().any(|detail| detail.key == "localSocketTtlLeg" && detail.value == "client_to_proxy"));
        assert!(!details.iter().any(|detail| detail.key == "observedServerTtl"));
        assert!(!details.iter().any(|detail| detail.key == "estimatedHopCount"));
    }
}
