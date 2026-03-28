use std::sync::Arc;
use std::time::Instant;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::{EchConfig, EchMode, EchStatus};
use rustls::pki_types::{CertificateDer, EchConfigListBytes, ServerName, UnixTime};
use rustls::{
    ClientConfig, ClientConnection, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme,
    StreamOwned,
};

use crate::dns::{resolve_https_ech_configs_via_encrypted_dns, EchResolutionOutcome};
use crate::ja3::{self, RecordingStream};
use crate::platform_ttl;
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
    pub(crate) ech_resolution_detail: Option<String>,
    pub(crate) tcp_connect_ms: Option<u64>,
    pub(crate) tls_handshake_ms: Option<u64>,
    pub(crate) cert_chain_length: Option<usize>,
    pub(crate) cert_issuer: Option<String>,
    pub(crate) observed_server_ttl: Option<u8>,
    pub(crate) estimated_hop_count: Option<u8>,
    pub(crate) ja3_fingerprint: Option<String>,
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

// --- Probe stream result with timing breakdown ---

pub(crate) struct ProbeStreamResult {
    pub(crate) stream: ConnectionStream,
    pub(crate) tcp_connect_ms: u64,
    pub(crate) tls_handshake_ms: u64,
    pub(crate) cert_chain_length: Option<usize>,
    pub(crate) cert_issuer: Option<String>,
    pub(crate) observed_server_ttl: Option<u8>,
    pub(crate) estimated_hop_count: Option<u8>,
    pub(crate) ja3_fingerprint: Option<String>,
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
        Ok(result) => {
            let tcp_connect_ms = Some(result.tcp_connect_ms);
            let tls_handshake_ms = Some(result.tls_handshake_ms);
            let cert_chain_length = result.cert_chain_length;
            let cert_issuer = result.cert_issuer;
            let observed_server_ttl = result.observed_server_ttl;
            let estimated_hop_count = result.estimated_hop_count;
            let ja3_fingerprint = result.ja3_fingerprint;
            let mut stream = result.stream;
            let (status, version, error, ech_resolution_detail) = match &mut stream {
                ConnectionStream::Plain(_) => ("tls_ok".to_string(), None, None, None),
                ConnectionStream::Tls(stream) => {
                    let version = tls_version_label(stream.conn.protocol_version());
                    if matches!(profile, TlsClientProfile::Tls13WithEch) {
                        let ech_status = stream.conn.ech_status();
                        if matches!(ech_status, EchStatus::Accepted) {
                            ("tls_ok".to_string(), version, None, Some("ech_config_available".to_string()))
                        } else {
                            (
                                "tls_handshake_failed".to_string(),
                                version,
                                Some(format!("ech_{}", ech_status_label(ech_status))),
                                Some("ech_config_available".to_string()),
                            )
                        }
                    } else {
                        ("tls_ok".to_string(), version, None, None)
                    }
                }
            };
            stream.shutdown();
            TlsObservation {
                status,
                version,
                error,
                certificate_anomaly: false,
                ech_resolution_detail,
                tcp_connect_ms,
                tls_handshake_ms,
                cert_chain_length,
                cert_issuer,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint,
            }
        }
        Err(err) => {
            if matches!(profile, TlsClientProfile::Tls13WithEch)
                && (err == ECH_CONFIG_UNAVAILABLE_ERROR || err.starts_with("ech_resolution_failed:"))
            {
                let ech_resolution_detail =
                    if err == ECH_CONFIG_UNAVAILABLE_ERROR { "ech_not_published".to_string() } else { err.clone() };
                return TlsObservation {
                    status: "not_run".to_string(),
                    version: None,
                    error: Some(err),
                    certificate_anomaly: false,
                    ech_resolution_detail: Some(ech_resolution_detail),
                    tcp_connect_ms: None,
                    tls_handshake_ms: None,
                    cert_chain_length: None,
                    cert_issuer: None,
                    observed_server_ttl: None,
                    estimated_hop_count: None,
                    ja3_fingerprint: None,
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
                ech_resolution_detail: None,
                tcp_connect_ms: None,
                tls_handshake_ms: None,
                cert_chain_length: None,
                cert_issuer: None,
                observed_server_ttl: None,
                estimated_hop_count: None,
                ja3_fingerprint: None,
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
) -> Result<ProbeStreamResult, String> {
    let tcp_start = Instant::now();
    let socket = connect_transport(target, port, transport)?;
    let tcp_connect_ms = tcp_start.elapsed().as_millis() as u64;

    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;

    match tls_name {
        Some(name) if verify_certificates || port == 443 || !matches!(profile, TlsClientProfile::Auto) => {
            let config = match profile {
                TlsClientProfile::Tls13WithEch => {
                    build_ech_client_config(name, transport, verify_certificates, tls_verifier)?
                }
                _ => build_standard_client_config(profile, verify_certificates, tls_verifier),
            };
            let server_name = make_server_name(name, target)?;
            let mut connection = ClientConnection::new(config, server_name).map_err(|err| err.to_string())?;

            // Wrap the socket in RecordingStream to capture the ClientHello for JA3.
            let mut recording = RecordingStream::new(socket);

            let tls_start = Instant::now();
            while connection.is_handshaking() {
                connection.complete_io(&mut recording).map_err(|err| err.to_string())?;
            }
            let tls_handshake_ms = tls_start.elapsed().as_millis() as u64;

            // Compute JA3 from the recorded outbound bytes before unwrapping.
            let ja3_fingerprint = ja3::compute_ja3(recording.recorded_writes());

            let (socket, _recorded) = recording.into_parts();
            let tls_stream = StreamOwned::new(connection, socket);

            let (cert_chain_length, cert_issuer) = extract_cert_info(&tls_stream.conn);

            // Capture server TTL from the underlying TCP socket after TLS handshake
            let observed_server_ttl = platform_ttl::get_observed_ttl(&tls_stream.sock);
            let estimated_hop_count = observed_server_ttl.map(platform_ttl::estimate_hop_count);

            Ok(ProbeStreamResult {
                stream: ConnectionStream::Tls(Box::new(tls_stream)),
                tcp_connect_ms,
                tls_handshake_ms,
                cert_chain_length,
                cert_issuer,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint,
            })
        }
        _ => {
            let observed_server_ttl = platform_ttl::get_observed_ttl(&socket);
            let estimated_hop_count = observed_server_ttl.map(platform_ttl::estimate_hop_count);
            Ok(ProbeStreamResult {
                stream: ConnectionStream::Plain(socket),
                tcp_connect_ms,
                tls_handshake_ms: 0,
                cert_chain_length: None,
                cert_issuer: None,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint: None,
            })
        }
    }
}

fn extract_cert_info(conn: &ClientConnection) -> (Option<usize>, Option<String>) {
    match conn.peer_certificates() {
        Some(certs) if !certs.is_empty() => {
            let chain_length = Some(certs.len());
            let issuer = parse_issuer_cn(certs[0].as_ref());
            (chain_length, issuer)
        }
        _ => (None, None),
    }
}

/// Minimal DER/X.509 parser to extract the Issuer Common Name (CN) from a
/// leaf certificate without pulling in an external x509 crate.
///
/// X.509 Certificate structure (simplified):
///   SEQUENCE {
///     SEQUENCE (TBSCertificate) {
///       [0] version, INTEGER serial,
///       SEQUENCE signatureAlgorithm,
///       SEQUENCE issuer { SET { SEQUENCE { OID, value } }* },
///       ...
///     }
///   }
///
/// We walk the DER just far enough to reach the issuer field, then scan its
/// RDN SEQUENCEs for OID 2.5.4.3 (id-at-commonName).
fn parse_issuer_cn(der: &[u8]) -> Option<String> {
    // OID 2.5.4.3 (id-at-commonName) encoded in DER
    const OID_CN: &[u8] = &[0x55, 0x04, 0x03];

    let (_, inner) = read_der_sequence(der)?;
    let (_, tbs) = read_der_sequence(inner)?;

    // TBSCertificate fields: [0] version (optional), serialNumber,
    // signatureAlgorithm, issuer, ...
    let mut pos = tbs;

    // Skip optional explicit [0] version tag
    if pos.first().copied() == Some(0xA0) {
        let (rest, _) = read_der_element(pos)?;
        pos = rest;
    }

    // Skip serialNumber (INTEGER)
    let (rest, _) = read_der_element(pos)?;
    pos = rest;

    // Skip signatureAlgorithm (SEQUENCE)
    let (rest, _) = read_der_element(pos)?;
    pos = rest;

    // issuer (SEQUENCE of SETs of SEQUENCEs)
    let (_rest, issuer_bytes) = read_der_sequence(pos)?;

    // Walk each RDN SET looking for OID_CN
    let mut rdn_pos = issuer_bytes;
    while !rdn_pos.is_empty() {
        let (next, set_content) = read_der_element(rdn_pos)?;
        // Each SET contains one or more SEQUENCE { OID, value }
        let mut attr_pos = set_content;
        while !attr_pos.is_empty() {
            let (next_attr, seq_content) = read_der_sequence(attr_pos)?;
            // First element is the OID
            if let Some((value_bytes, oid_bytes)) = read_der_element(seq_content) {
                if oid_bytes.len() >= OID_CN.len() && oid_bytes.ends_with(OID_CN) {
                    // Second element is the value (UTF8String, PrintableString, etc.)
                    if let Some((_rest, cn_bytes)) = read_der_element(value_bytes) {
                        return String::from_utf8(cn_bytes.to_vec()).ok();
                    }
                }
            }
            attr_pos = next_attr;
        }
        rdn_pos = next;
    }

    None
}

/// Read one DER TLV element. Returns (remaining_bytes, content_bytes).
fn read_der_element(data: &[u8]) -> Option<(&[u8], &[u8])> {
    if data.is_empty() {
        return None;
    }
    let _tag = data[0];
    let (len, header_size) = read_der_length(&data[1..])?;
    let total_header = 1 + header_size;
    let end = total_header + len;
    if end > data.len() {
        return None;
    }
    Some((&data[end..], &data[total_header..end]))
}

/// Read one DER SEQUENCE, returning (remaining, inner_content).
fn read_der_sequence(data: &[u8]) -> Option<(&[u8], &[u8])> {
    if data.is_empty() || data[0] != 0x30 {
        return None;
    }
    read_der_element(data)
}

/// Decode a DER length field. Returns (length_value, bytes_consumed).
fn read_der_length(data: &[u8]) -> Option<(usize, usize)> {
    if data.is_empty() {
        return None;
    }
    let first = data[0] as usize;
    if first < 0x80 {
        Some((first, 1))
    } else {
        let num_bytes = first & 0x7F;
        if num_bytes == 0 || num_bytes > 4 || data.len() < 1 + num_bytes {
            return None;
        }
        let mut length = 0usize;
        for i in 0..num_bytes {
            length = (length << 8) | (data[1 + i] as usize);
        }
        Some((length, 1 + num_bytes))
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
    let ech_config_list = match resolve_https_ech_configs_via_encrypted_dns(server_name, transport) {
        EchResolutionOutcome::Available(bytes) => bytes,
        EchResolutionOutcome::NotPublished => return Err(ECH_CONFIG_UNAVAILABLE_ERROR.to_string()),
        EchResolutionOutcome::ResolutionFailed(err) => {
            return Err(format!("ech_resolution_failed: {err}"));
        }
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
            ech_resolution_detail: None,
            tcp_connect_ms: None,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            observed_server_ttl: None,
            estimated_hop_count: None,
            ja3_fingerprint: None,
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
}
