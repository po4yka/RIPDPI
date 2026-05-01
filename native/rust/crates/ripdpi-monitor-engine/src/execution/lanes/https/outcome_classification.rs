use crate::tls::TlsObservation;

use super::observation_collection::HttpsObservationCollection;

pub(super) fn classify_https_outcome(observations: &HttpsObservationCollection) -> String {
    if observations.tls13.certificate_anomaly || observations.tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if observations.tls13.status == "tls_ok" && observations.tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if observations.tls13.status == "tls_ok" || observations.tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else if observations.tls_ech.status == "tls_ok" {
        "tls_ech_only".to_string()
    } else {
        "tls_handshake_failed".to_string()
    }
}

pub(super) fn https_tls_error_detail(
    outcome: &str,
    tls13: &TlsObservation,
    tls12: &TlsObservation,
    tls_ech: &TlsObservation,
) -> String {
    let include_ech_error = !matches!(outcome, "tls_ok" | "tls_version_split");
    tls13
        .error
        .clone()
        .or_else(|| tls12.error.clone())
        .or_else(|| include_ech_error.then(|| tls_ech.error.clone()).flatten())
        .unwrap_or_else(|| "none".to_string())
}
