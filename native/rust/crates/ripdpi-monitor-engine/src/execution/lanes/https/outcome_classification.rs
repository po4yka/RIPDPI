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
