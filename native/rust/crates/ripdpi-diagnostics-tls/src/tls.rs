mod certs;
mod classification;
mod config;
mod key_log;
mod probe;
mod types;
mod verifier;

pub use classification::{
    append_tls_comparison_details, classify_tls_signal, ech_status_label, preferred_tls_observation, tls_version_label,
};
pub use config::{default_root_store, make_server_name, planned_tls_template_metadata, planned_tls_template_profile};
pub use key_log::{TlsKeyLogCallback, TlsKeyLogFile, tls_key_log_callback_for_path};
pub use probe::{
    open_probe_stream, open_probe_stream_targets_with_key_log_and_abort, open_probe_stream_targets_with_options,
    open_probe_stream_targets_with_options_and_abort, open_probe_stream_with_abort, open_probe_stream_with_key_log,
    open_probe_stream_with_key_log_and_abort, try_tls_handshake, try_tls_handshake_targets,
    try_tls_handshake_targets_with_key_log, try_tls_handshake_targets_with_options_and_abort,
    try_tls_handshake_with_key_log,
};
pub use types::{
    ApplicationProtocolPolicy, ClientHelloCapture, ProbeStreamError, ProbeStreamFailureStage, ProbeStreamOptions,
    ProbeStreamResult, TlsClientProfile, TlsObservation,
};
pub use verifier::NoCertificateVerification;

#[cfg(test)]
mod tests {
    use super::classification::first_flight_plan_label;
    use super::config::build_standard_client_config_with_key_log;
    use super::*;
    use crate::transport::TargetAddress;
    use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;
    use rustls::ClientConfig;
    use rustls::pki_types::ServerName;
    use std::net::IpAddr;

    fn obs(status: &str, cert_anomaly: bool) -> TlsObservation {
        TlsObservation {
            status: status.to_string(),
            version: None,
            error: None,
            failure_stage: None,
            failure_duration_ms: None,
            certificate_anomaly: cert_anomaly,
            ech_resolution_detail: None,
            ech_bootstrap_policy: None,
            ech_bootstrap_resolver_id: None,
            ech_outer_extension_policy: None,
            ech_first_flight_plan: None,
            transport_open_ms: None,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            local_socket_ttl: None,
            ja3_fingerprint: None,
            client_hello_capture: ClientHelloCapture::NotSent,
            tls_alert_code: None,
            tls_alert_description: None,
            tls_server_hello_received: None,
            connected_addr: None,
            local_addr: None,
            cdn_provider: None,
            route_report: None,
        }
    }

    #[test]
    fn make_server_name_with_hostname() {
        let target = TargetAddress::Host("fallback.example.com".to_string());
        let result = make_server_name("example.com", &target).unwrap();
        assert_eq!(result, ServerName::try_from("example.com".to_string()).unwrap());
    }

    #[test]
    fn make_server_name_with_ip_target() {
        let ip: IpAddr = "1.2.3.4".parse().unwrap();
        let target = TargetAddress::Ip(ip);
        let result = make_server_name("", &target).unwrap();
        assert_eq!(result, ServerName::IpAddress(ip.into()));
    }

    #[test]
    fn make_server_name_empty_string_falls_back_to_host() {
        let target = TargetAddress::Host("fallback.example.com".to_string());
        let result = make_server_name("", &target).unwrap();
        assert_eq!(result, ServerName::try_from("fallback.example.com".to_string()).unwrap());
    }

    #[test]
    fn tls_version_label_tls12() {
        assert_eq!(tls_version_label(Some(rustls::ProtocolVersion::TLSv1_2)), Some("TLS1.2".to_string()));
    }

    #[test]
    fn tls_version_label_tls13() {
        assert_eq!(tls_version_label(Some(rustls::ProtocolVersion::TLSv1_3)), Some("TLS1.3".to_string()));
    }

    #[test]
    fn tls_version_label_none() {
        assert_eq!(tls_version_label(None), None);
    }

    #[test]
    fn tls_version_label_unknown() {
        let unknown = rustls::ProtocolVersion::Unknown(0x0200);
        let result = tls_version_label(Some(unknown));
        assert!(result.is_some());
    }

    #[test]
    fn classify_tls_signal_cert_invalid() {
        assert_eq!(classify_tls_signal(&obs("tls_ok", true), &obs("tls_ok", false)), "tls_cert_invalid");
        assert_eq!(classify_tls_signal(&obs("tls_ok", false), &obs("tls_ok", true)), "tls_cert_invalid");
    }

    #[test]
    fn classify_tls_signal_consistent() {
        assert_eq!(classify_tls_signal(&obs("tls_ok", false), &obs("tls_ok", false)), "tls_consistent");
    }

    #[test]
    fn classify_tls_signal_version_split() {
        assert_eq!(
            classify_tls_signal(&obs("tls_ok", false), &obs("tls_handshake_failed", false)),
            "tls_version_split_low_confidence"
        );
        assert_eq!(
            classify_tls_signal(&obs("tls_handshake_failed", false), &obs("tls_ok", false)),
            "tls_version_split_low_confidence"
        );
    }

    #[test]
    fn classify_tls_signal_unavailable() {
        assert_eq!(
            classify_tls_signal(&obs("tls_handshake_failed", false), &obs("tls_handshake_failed", false)),
            "tls_unavailable"
        );
    }

    #[test]
    fn preferred_tls_observation_prefers_cert_anomaly_tls13() {
        let tls13 = obs("tls_ok", true);
        let tls12 = obs("tls_ok", false);
        let result = preferred_tls_observation(&tls13, &tls12);
        assert!(std::ptr::eq(result, &tls13));
    }

    #[test]
    fn preferred_tls_observation_prefers_cert_anomaly_tls12() {
        let tls13 = obs("tls_handshake_failed", false);
        let tls12 = obs("tls_ok", true);
        let result = preferred_tls_observation(&tls13, &tls12);
        assert!(std::ptr::eq(result, &tls12));
    }

    #[test]
    fn preferred_tls_observation_prefers_tls13_ok() {
        let tls13 = obs("tls_ok", false);
        let tls12 = obs("tls_handshake_failed", false);
        let result = preferred_tls_observation(&tls13, &tls12);
        assert!(std::ptr::eq(result, &tls13));
    }

    #[test]
    fn preferred_tls_observation_prefers_tls12_ok() {
        let tls13 = obs("tls_handshake_failed", false);
        let tls12 = obs("tls_ok", false);
        let result = preferred_tls_observation(&tls13, &tls12);
        assert!(std::ptr::eq(result, &tls12));
    }

    #[test]
    fn preferred_tls_observation_defaults_to_tls13() {
        let tls13 = obs("tls_handshake_failed", false);
        let tls12 = obs("tls_handshake_failed", false);
        let result = preferred_tls_observation(&tls13, &tls12);
        assert!(std::ptr::eq(result, &tls13));
    }

    #[test]
    fn tls13_with_ech_uses_tls13_protocol_version() {
        // The ECH profile is TLS 1.3-only, even before live ECH config discovery.
        let profile = TlsClientProfile::Tls13WithEch;
        let _builder = match profile {
            TlsClientProfile::Auto => {
                ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                    .with_safe_default_protocol_versions()
                    .expect("ring provider supports default TLS versions")
            }
            TlsClientProfile::Tls12Only => {
                ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                    .with_protocol_versions(&[&rustls::version::TLS12])
                    .expect("ring provider supports TLS1.2")
            }
            TlsClientProfile::Tls13Only | TlsClientProfile::Tls13WithEch => {
                ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                    .with_protocol_versions(&[&rustls::version::TLS13])
                    .expect("ring provider supports TLS1.3")
            }
        };
    }

    #[test]
    fn tls_client_profile_ech_variant_is_distinct() {
        let profile = TlsClientProfile::Tls13WithEch;
        let debug = format!("{profile:?}");
        assert!(debug.contains("WithEch"), "expected WithEch in debug repr, got: {debug}");
    }

    #[test]
    fn planned_tls_template_profiles_match_phase_eleven_catalog() {
        assert_eq!("chrome_stable", planned_tls_template_profile(TlsClientProfile::Tls13Only));
        assert_eq!("chrome_desktop_stable", planned_tls_template_profile(TlsClientProfile::Tls12Only));
        assert_eq!("firefox_ech_stable", planned_tls_template_profile(TlsClientProfile::Tls13WithEch));

        let ech = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
        assert!(ech.template.ech_capable);
        assert_eq!("firefox_ech_grease", ech.template.grease_style);
        assert_eq!("https_rr_or_cdn_fallback", ech.template.ech_bootstrap_policy);
        assert_eq!(Some("adguard"), ech.template.ech_bootstrap_resolver_id);
        assert_eq!("preserve_ech_or_grease", ech.template.ech_outer_extension_policy);
    }

    #[test]
    fn first_flight_plan_label_captures_ech_bootstrap_and_outer_policy() {
        let plan = TlsTemplateFirstFlightPlan {
            record_choreography: ripdpi_tls_profiles::RecordChoreography::SniEchTailAdaptive,
            record_payload_boundaries: vec![120, 240],
            ech_bootstrap_policy: "https_rr_or_cdn_fallback",
            ech_bootstrap_resolver_id: Some("adguard"),
            ech_outer_extension_policy: "preserve_ech_or_grease",
            grease_style: "firefox_ech_grease",
            ech_capable_template: true,
            ech_present_in_input: true,
        };

        assert_eq!(
            first_flight_plan_label(&plan),
            "sni_ech_tail_adaptive|https_rr_or_cdn_fallback|resolver=adguard|outer=preserve_ech_or_grease|ech_present=true"
        );
    }

    #[test]
    fn standard_client_config_uses_template_alpn() {
        let config = build_standard_client_config_with_key_log(
            TlsClientProfile::Tls13Only,
            None,
            None,
            ApplicationProtocolPolicy::TemplateDefault,
        );
        assert_eq!(config.alpn_protocols, vec![b"h2".to_vec(), b"http/1.1".to_vec()]);
    }

    #[test]
    fn http11_application_protocol_policy_restricts_alpn() {
        let config = build_standard_client_config_with_key_log(
            TlsClientProfile::Auto,
            None,
            None,
            ApplicationProtocolPolicy::Http11Only,
        );
        assert_eq!(config.alpn_protocols, vec![b"http/1.1".to_vec()]);
    }
}
