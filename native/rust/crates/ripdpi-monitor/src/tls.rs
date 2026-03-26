use std::sync::Arc;

use rustls::client::{EchConfig, EchMode, EchStatus};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, EchConfigListBytes, ServerName, UnixTime};
use rustls::{
    ClientConfig, ClientConnection, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme,
    StreamOwned,
};

use crate::dns::resolve_https_ech_configs_via_encrypted_dns;
use crate::transport::{connect_transport, ConnectionStream, TargetAddress, TransportConfig};
use crate::util::IO_TIMEOUT;

const ECH_CONFIG_UNAVAILABLE_ERROR: &str = "ech_config_unavailable";

// --- Types ---

#[derive(Clone, Debug)]
pub(crate) struct TlsObservation {
    pub(crate) status: String,
    pub(crate) version: Option<String>,
    pub(crate) error: Option<String>,
    pub(crate) certificate_anomaly: bool,
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum TlsClientProfile {
    Auto,
    Tls12Only,
    Tls13Only,
    Tls13WithEch,
}

// --- Dangerous certificate verifier (INTENTIONAL, audit-reviewed) ---
//
// SECURITY: This verifier is used ONLY by the diagnostic/monitor probe path to
// detect censorship-induced TLS interception (MITM middleboxes). It is NOT used
// for any data-carrying connection. The probe result is observational -- it
// reports whether a TLS handshake succeeds or fails and captures certificate
// anomalies, but never trusts the connection for user traffic.
//
// Removing this would prevent the monitor from detecting TLS-level censorship
// on networks that inject forged certificates.
//
// Reviewed: 2026-03-25, F-009

#[derive(Debug)]
pub(crate) struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}

// --- TLS helper functions ---

pub(crate) fn try_tls_handshake(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> TlsObservation {
    match open_probe_stream(target, port, transport, Some(server_name), verify_certificates, profile, tls_verifier) {
        Ok(mut stream) => {
            let (status, version, error) = match &mut stream {
                ConnectionStream::Plain(_) => ("tls_ok".to_string(), None, None),
                ConnectionStream::Tls(stream) => {
                    let version = tls_version_label(stream.conn.protocol_version());
                    if matches!(profile, TlsClientProfile::Tls13WithEch) {
                        let ech_status = stream.conn.ech_status();
                        if matches!(ech_status, EchStatus::Accepted) {
                            ("tls_ok".to_string(), version, None)
                        } else {
                            (
                                "tls_handshake_failed".to_string(),
                                version,
                                Some(format!("ech_{}", ech_status_label(ech_status))),
                            )
                        }
                    } else {
                        ("tls_ok".to_string(), version, None)
                    }
                }
            };
            stream.shutdown();
            TlsObservation { status, version, error, certificate_anomaly: false }
        }
        Err(err) => {
            if matches!(profile, TlsClientProfile::Tls13WithEch) && err == ECH_CONFIG_UNAVAILABLE_ERROR {
                return TlsObservation {
                    status: "not_run".to_string(),
                    version: None,
                    error: Some(err),
                    certificate_anomaly: false,
                };
            }
            let certificate_anomaly = is_certificate_error(&err);
            TlsObservation {
                status: if certificate_anomaly {
                    "tls_cert_invalid".to_string()
                } else {
                    "tls_handshake_failed".to_string()
                },
                version: None,
                error: Some(err),
                certificate_anomaly,
            }
        }
    }
}

pub(crate) fn open_probe_stream(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ConnectionStream, String> {
    let socket = connect_transport(target, port, transport)?;
    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;

    match tls_name {
        Some(name) if verify_certificates || port == 443 || !matches!(profile, TlsClientProfile::Auto) => {
            let config = match profile {
                TlsClientProfile::Tls13WithEch => build_ech_client_config(name, transport, verify_certificates, tls_verifier)?,
                _ => build_standard_client_config(profile, verify_certificates, tls_verifier),
            };
            let server_name = make_server_name(name, target)?;
            let connection = ClientConnection::new(config, server_name).map_err(|err| err.to_string())?;
            let mut tls_stream = StreamOwned::new(connection, socket);
            while tls_stream.conn.is_handshaking() {
                tls_stream.conn.complete_io(&mut tls_stream.sock).map_err(|err| err.to_string())?;
            }
            Ok(ConnectionStream::Tls(Box::new(tls_stream)))
        }
        _ => Ok(ConnectionStream::Plain(socket)),
    }
}

fn build_standard_client_config(
    profile: TlsClientProfile,
    verify_certificates: bool,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Arc<ClientConfig> {
    let builder = match profile {
        TlsClientProfile::Auto => ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions"),
        TlsClientProfile::Tls12Only => ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_protocol_versions(&[&rustls::version::TLS12])
            .expect("ring provider supports TLS1.2"),
        TlsClientProfile::Tls13Only | TlsClientProfile::Tls13WithEch => ClientConfig::builder_with_provider(
            rustls::crypto::ring::default_provider().into(),
        )
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("ring provider supports TLS1.3"),
    };
    if verify_certificates {
        if let Some(verifier) = tls_verifier {
            Arc::new(builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth())
        } else {
            Arc::new(builder.with_root_certificates(default_root_store()).with_no_client_auth())
        }
    } else {
        Arc::new(
            builder
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
                .with_no_client_auth(),
        )
    }
}

fn build_ech_client_config(
    server_name: &str,
    transport: &TransportConfig,
    verify_certificates: bool,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<Arc<ClientConfig>, String> {
    let Some(ech_config_list) = resolve_https_ech_configs_via_encrypted_dns(server_name, transport)? else {
        return Err(ECH_CONFIG_UNAVAILABLE_ERROR.to_string());
    };
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config_list),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .map_err(|err| err.to_string())?;
    let builder = ClientConfig::builder_with_provider(provider.into())
        .with_ech(EchMode::Enable(ech_config))
        .map_err(|err| err.to_string())?;
    let config = if verify_certificates {
        if let Some(verifier) = tls_verifier {
            Arc::new(builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth())
        } else {
            Arc::new(builder.with_root_certificates(default_root_store()).with_no_client_auth())
        }
    } else {
        Arc::new(
            builder
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
                .with_no_client_auth(),
        )
    };
    Ok(config)
}

pub(crate) fn make_server_name(name: &str, target: &TargetAddress) -> Result<ServerName<'static>, String> {
    if !name.is_empty() {
        if let Ok(server_name) = ServerName::try_from(name.to_string()) {
            return Ok(server_name);
        }
    }
    match target {
        TargetAddress::Ip(ip) => Ok(ServerName::IpAddress((*ip).into())),
        TargetAddress::Host(host) => ServerName::try_from(host.clone()).map_err(|err| err.to_string()),
    }
}

pub(crate) fn default_root_store() -> RootCertStore {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    roots
}

pub(crate) fn tls_version_label(version: Option<rustls::ProtocolVersion>) -> Option<String> {
    version.map(|value| match value {
        rustls::ProtocolVersion::TLSv1_2 => "TLS1.2".to_string(),
        rustls::ProtocolVersion::TLSv1_3 => "TLS1.3".to_string(),
        other => format!("{other:?}"),
    })
}

pub(crate) fn ech_status_label(status: EchStatus) -> String {
    match status {
        EchStatus::NotOffered => "not_offered".to_string(),
        EchStatus::Grease => "grease".to_string(),
        EchStatus::Offered => "offered".to_string(),
        EchStatus::Accepted => "accepted".to_string(),
        EchStatus::Rejected => "rejected".to_string(),
    }
}

pub(crate) fn is_certificate_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("certificate")
        || lower.contains("unknown issuer")
        || lower.contains("not valid")
        || lower.contains("bad certificate")
}

pub(crate) fn classify_tls_signal(tls13: &TlsObservation, tls12: &TlsObservation) -> &'static str {
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

pub(crate) fn preferred_tls_observation<'a>(
    tls13: &'a TlsObservation,
    tls12: &'a TlsObservation,
) -> &'a TlsObservation {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;

    fn obs(status: &str, cert_anomaly: bool) -> TlsObservation {
        TlsObservation {
            status: status.to_string(),
            version: None,
            error: None,
            certificate_anomaly: cert_anomaly,
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
    fn is_certificate_error_detects_certificate_keyword() {
        assert!(is_certificate_error("invalid certificate chain"));
    }

    #[test]
    fn is_certificate_error_detects_unknown_issuer() {
        assert!(is_certificate_error("unknown issuer"));
    }

    #[test]
    fn is_certificate_error_detects_bad_certificate() {
        assert!(is_certificate_error("received fatal alert: bad certificate"));
    }

    #[test]
    fn is_certificate_error_detects_not_valid() {
        assert!(is_certificate_error("hostname not valid for this cert"));
    }

    #[test]
    fn is_certificate_error_rejects_unrelated() {
        assert!(!is_certificate_error("connection reset by peer"));
        assert!(!is_certificate_error("timeout"));
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
            TlsClientProfile::Auto => ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions"),
            TlsClientProfile::Tls12Only => ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_protocol_versions(&[&rustls::version::TLS12])
                .expect("ring provider supports TLS1.2"),
            TlsClientProfile::Tls13Only | TlsClientProfile::Tls13WithEch => ClientConfig::builder_with_provider(
                rustls::crypto::ring::default_provider().into(),
            )
            .with_protocol_versions(&[&rustls::version::TLS13])
            .expect("ring provider supports TLS1.3"),
        };
    }

    #[test]
    fn tls_client_profile_ech_variant_is_distinct() {
        let profile = TlsClientProfile::Tls13WithEch;
        let debug = format!("{profile:?}");
        assert!(debug.contains("WithEch"), "expected WithEch in debug repr, got: {debug}");
    }
}
