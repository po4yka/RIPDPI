use std::sync::Arc;
use std::time::Instant;

use ripdpi_tls_profiles::{
    plan_first_flight, selected_profile_config, selected_profile_metadata, ProfileMetadata, TlsTemplateFirstFlightPlan,
};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::{EchConfig, EchMode, EchStatus};
use rustls::pki_types::{CertificateDer, EchConfigListBytes, ServerName, UnixTime};
use rustls::{
    ClientConfig, ClientConnection, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme,
    StreamOwned,
};

use crate::cdn_ech::{opportunistic_ech_config_for_ip, opportunistic_ech_provider_for_ip};
use crate::dns::{
    encrypted_dns_endpoint_for_resolver_id, resolve_https_ech_configs_via_encrypted_dns_with_endpoint,
    EchResolutionOutcome,
};
use crate::ja3::{self, RecordingStream};
use crate::platform_ttl;
use crate::transport::{connect_transport_observed, ConnectionStream, TargetAddress, TransportConfig};
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
    pub(crate) ech_bootstrap_policy: Option<String>,
    pub(crate) ech_bootstrap_resolver_id: Option<String>,
    pub(crate) ech_outer_extension_policy: Option<String>,
    pub(crate) ech_first_flight_plan: Option<String>,
    pub(crate) tcp_connect_ms: Option<u64>,
    pub(crate) tls_handshake_ms: Option<u64>,
    pub(crate) cert_chain_length: Option<usize>,
    pub(crate) cert_issuer: Option<String>,
    pub(crate) observed_server_ttl: Option<u8>,
    pub(crate) estimated_hop_count: Option<u8>,
    pub(crate) ja3_fingerprint: Option<String>,
    /// Numeric TLS alert code when the handshake fails with AlertReceived.
    pub(crate) tls_alert_code: Option<u8>,
    /// Human-readable alert description (e.g. "HandshakeFailure").
    pub(crate) tls_alert_description: Option<String>,
    /// Whether a ServerHello was received before the error occurred.
    pub(crate) tls_server_hello_received: Option<bool>,
    /// DPI firmware signature inferred from the alert code and timing.
    pub(crate) tls_dpi_signature: Option<String>,
    pub(crate) connected_addr: Option<std::net::SocketAddr>,
    pub(crate) cdn_provider: Option<String>,
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
    pub(crate) tls_template_first_flight_plan: Option<TlsTemplateFirstFlightPlan>,
    pub(crate) tcp_connect_ms: u64,
    pub(crate) tls_handshake_ms: u64,
    pub(crate) cert_chain_length: Option<usize>,
    pub(crate) cert_issuer: Option<String>,
    pub(crate) observed_server_ttl: Option<u8>,
    pub(crate) estimated_hop_count: Option<u8>,
    pub(crate) ja3_fingerprint: Option<String>,
    pub(crate) connected_addr: Option<std::net::SocketAddr>,
    pub(crate) cdn_provider: Option<String>,
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
    try_tls_handshake_targets(
        std::slice::from_ref(target),
        port,
        transport,
        server_name,
        verify_certificates,
        profile,
        tls_verifier,
    )
}

pub(crate) fn try_tls_handshake_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> TlsObservation {
    match open_probe_stream_targets(
        targets,
        port,
        transport,
        Some(server_name),
        verify_certificates,
        profile,
        tls_verifier,
    ) {
        Ok(result) => {
            let tcp_connect_ms = Some(result.tcp_connect_ms);
            let tls_handshake_ms = Some(result.tls_handshake_ms);
            let cert_chain_length = result.cert_chain_length;
            let cert_issuer = result.cert_issuer;
            let observed_server_ttl = result.observed_server_ttl;
            let estimated_hop_count = result.estimated_hop_count;
            let ja3_fingerprint = result.ja3_fingerprint;
            let connected_addr = result.connected_addr;
            let cdn_provider = result.cdn_provider;
            let tls_template_first_flight_plan = result.tls_template_first_flight_plan;
            let mut stream = result.stream;
            let (
                status,
                version,
                error,
                ech_resolution_detail,
                ech_bootstrap_policy,
                ech_bootstrap_resolver_id,
                ech_outer_extension_policy,
                ech_first_flight_plan,
            ) = match &mut stream {
                ConnectionStream::Plain(_) => ("tls_ok".to_string(), None, None, None, None, None, None, None),
                ConnectionStream::Tls(stream) => {
                    let version = tls_version_label(stream.conn.protocol_version());
                    if matches!(profile, TlsClientProfile::Tls13WithEch) {
                        let ech_status = stream.conn.ech_status();
                        let plan = tls_template_first_flight_plan.as_ref();
                        if matches!(ech_status, EchStatus::Accepted) {
                            (
                                "tls_ok".to_string(),
                                version,
                                None,
                                Some("ech_config_available".to_string()),
                                plan.map(|value| value.ech_bootstrap_policy.to_string()),
                                plan.and_then(|value| value.ech_bootstrap_resolver_id.map(ToString::to_string)),
                                plan.map(|value| value.ech_outer_extension_policy.to_string()),
                                plan.map(first_flight_plan_label),
                            )
                        } else {
                            (
                                "tls_handshake_failed".to_string(),
                                version,
                                Some(format!("ech_{}", ech_status_label(ech_status))),
                                Some("ech_config_available".to_string()),
                                plan.map(|value| value.ech_bootstrap_policy.to_string()),
                                plan.and_then(|value| value.ech_bootstrap_resolver_id.map(ToString::to_string)),
                                plan.map(|value| value.ech_outer_extension_policy.to_string()),
                                plan.map(first_flight_plan_label),
                            )
                        }
                    } else {
                        ("tls_ok".to_string(), version, None, None, None, None, None, None)
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
                ech_bootstrap_policy,
                ech_bootstrap_resolver_id,
                ech_outer_extension_policy,
                ech_first_flight_plan,
                tcp_connect_ms,
                tls_handshake_ms,
                cert_chain_length,
                cert_issuer,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint,
                tls_alert_code: None,
                tls_alert_description: None,
                tls_server_hello_received: Some(true),
                tls_dpi_signature: None,
                connected_addr,
                cdn_provider,
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
                    ech_bootstrap_policy: Some(
                        planned_tls_template_metadata(TlsClientProfile::Tls13WithEch)
                            .template
                            .ech_bootstrap_policy
                            .to_string(),
                    ),
                    ech_bootstrap_resolver_id: planned_tls_template_metadata(TlsClientProfile::Tls13WithEch)
                        .template
                        .ech_bootstrap_resolver_id
                        .map(ToString::to_string),
                    ech_outer_extension_policy: Some(
                        planned_tls_template_metadata(TlsClientProfile::Tls13WithEch)
                            .template
                            .ech_outer_extension_policy
                            .to_string(),
                    ),
                    ech_first_flight_plan: None,
                    tcp_connect_ms: None,
                    tls_handshake_ms: None,
                    cert_chain_length: None,
                    cert_issuer: None,
                    observed_server_ttl: None,
                    estimated_hop_count: None,
                    ja3_fingerprint: None,
                    tls_alert_code: None,
                    tls_alert_description: None,
                    tls_server_hello_received: None,
                    tls_dpi_signature: None,
                    connected_addr: None,
                    cdn_provider: None,
                };
            }
            let certificate_anomaly = is_certificate_error(&err);
            let (tls_alert_code, tls_alert_description) = parse_alert_from_error(&err);
            let tls_server_hello_received = Some(err.contains("AlertReceived"));
            let tls_dpi_signature = tls_alert_code.and_then(|code| classify_tls_dpi_signature(code, None));
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
                ech_bootstrap_policy: None,
                ech_bootstrap_resolver_id: None,
                ech_outer_extension_policy: None,
                ech_first_flight_plan: None,
                tcp_connect_ms: None,
                tls_handshake_ms: None,
                cert_chain_length: None,
                cert_issuer: None,
                observed_server_ttl: None,
                estimated_hop_count: None,
                ja3_fingerprint: None,
                tls_alert_code,
                tls_alert_description,
                tls_server_hello_received,
                tls_dpi_signature,
                connected_addr: None,
                cdn_provider: None,
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
    open_probe_stream_targets(
        std::slice::from_ref(target),
        port,
        transport,
        tls_name,
        verify_certificates,
        profile,
        tls_verifier,
    )
}

pub(crate) fn open_probe_stream_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ProbeStreamResult, String> {
    let tcp_start = Instant::now();
    let transport_result = connect_transport_observed(targets, port, transport)?;
    let tcp_connect_ms = tcp_start.elapsed().as_millis() as u64;
    let connected_addr = transport_result.connected_addr;
    let cdn_provider = connected_addr.and_then(|addr| opportunistic_ech_provider_for_ip(addr.ip()).map(str::to_string));
    let socket = transport_result.stream;

    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;

    match tls_name {
        Some(name) if verify_certificates || port == 443 || !matches!(profile, TlsClientProfile::Auto) => {
            let target = targets.first().ok_or_else(|| "no_tls_targets".to_string())?;
            let template_profile = planned_tls_template_profile(profile);
            let config = match profile {
                TlsClientProfile::Tls13WithEch => build_ech_client_config(name, target, transport, tls_verifier)?,
                _ => build_standard_client_config(profile, tls_verifier),
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
            let tls_template_first_flight_plan = plan_first_flight(template_profile, recording.recorded_writes());

            let (socket, _recorded) = recording.into_parts();
            let tls_stream = StreamOwned::new(connection, socket);

            let (cert_chain_length, cert_issuer) = extract_cert_info(&tls_stream.conn);

            // Capture server TTL from the underlying TCP socket after TLS handshake
            let observed_server_ttl = platform_ttl::get_observed_ttl(&tls_stream.sock);
            let estimated_hop_count = observed_server_ttl.map(platform_ttl::estimate_hop_count);

            Ok(ProbeStreamResult {
                stream: ConnectionStream::Tls(Box::new(tls_stream)),
                tls_template_first_flight_plan,
                tcp_connect_ms,
                tls_handshake_ms,
                cert_chain_length,
                cert_issuer,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint,
                connected_addr,
                cdn_provider,
            })
        }
        _ => {
            let observed_server_ttl = platform_ttl::get_observed_ttl(&socket);
            let estimated_hop_count = observed_server_ttl.map(platform_ttl::estimate_hop_count);
            Ok(ProbeStreamResult {
                stream: ConnectionStream::Plain(socket),
                tls_template_first_flight_plan: None,
                tcp_connect_ms,
                tls_handshake_ms: 0,
                cert_chain_length: None,
                cert_issuer: None,
                observed_server_ttl,
                estimated_hop_count,
                ja3_fingerprint: None,
                connected_addr,
                cdn_provider,
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
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Arc<ClientConfig> {
    let template_profile = planned_tls_template_profile(profile);
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
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        builder.with_root_certificates(default_root_store()).with_no_client_auth()
    };
    apply_template_alpn(&mut config, template_profile);
    Arc::new(config)
}

fn build_ech_client_config(
    server_name: &str,
    target: &TargetAddress,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<Arc<ClientConfig>, String> {
    let template_metadata = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
    let bootstrap_endpoint = template_metadata
        .template
        .ech_bootstrap_resolver_id
        .map(encrypted_dns_endpoint_for_resolver_id)
        .unwrap_or_else(|| encrypted_dns_endpoint_for_resolver_id("adguard"));
    let ech_config_list =
        match resolve_https_ech_configs_via_encrypted_dns_with_endpoint(server_name, bootstrap_endpoint, transport) {
            EchResolutionOutcome::Available(bytes) => bytes,
            dns_failure => {
                // Opportunistic fallback: if the target IP belongs to a known CDN
                // that supports ECH, use a hardcoded config instead of giving up.
                if let Some(cdn_config) = resolve_opportunistic_ech(target) {
                    tracing::debug!(
                        server_name,
                        cdn = cdn_config.provider,
                        "ECH DNS resolution unavailable; using opportunistic CDN config"
                    );
                    cdn_config.ech_config_list.to_vec()
                } else {
                    return match dns_failure {
                        EchResolutionOutcome::NotPublished => Err(ECH_CONFIG_UNAVAILABLE_ERROR.to_string()),
                        EchResolutionOutcome::ResolutionFailed(err) => Err(format!("ech_resolution_failed: {err}")),
                        EchResolutionOutcome::Available(_) => unreachable!(),
                    };
                }
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
    let template_profile = planned_tls_template_profile(TlsClientProfile::Tls13WithEch);
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        builder.with_root_certificates(default_root_store()).with_no_client_auth()
    };
    apply_template_alpn(&mut config, template_profile);
    Ok(Arc::new(config))
}

/// Attempt to find an opportunistic ECH config by checking if the target IP
/// belongs to a known CDN provider.
fn resolve_opportunistic_ech(target: &TargetAddress) -> Option<&'static crate::cdn_ech::CdnEchConfig> {
    match target {
        TargetAddress::Ip(ip) => opportunistic_ech_config_for_ip(*ip),
        TargetAddress::Host(_) => None,
    }
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

pub(crate) fn planned_tls_template_profile(profile: TlsClientProfile) -> &'static str {
    match profile {
        TlsClientProfile::Auto | TlsClientProfile::Tls13Only => "chrome_stable",
        TlsClientProfile::Tls12Only => "chrome_desktop_stable",
        TlsClientProfile::Tls13WithEch => "firefox_ech_stable",
    }
}

pub(crate) fn planned_tls_template_metadata(profile: TlsClientProfile) -> ProfileMetadata {
    selected_profile_metadata(planned_tls_template_profile(profile))
}

fn first_flight_plan_label(plan: &TlsTemplateFirstFlightPlan) -> String {
    format!(
        "{}|{}|resolver={}|outer={}|ech_present={}",
        plan.record_choreography.as_str(),
        plan.ech_bootstrap_policy,
        plan.ech_bootstrap_resolver_id.unwrap_or("none"),
        plan.ech_outer_extension_policy,
        plan.ech_present_in_input
    )
}

fn apply_template_alpn(config: &mut ClientConfig, profile_id: &str) {
    config.alpn_protocols = selected_profile_config(profile_id).alpn.iter().map(|protocol| protocol.to_vec()).collect();
}

/// Parse the TLS alert code and description from a stringified rustls error.
/// Rustls formats `AlertReceived` as `"received fatal alert: {alert:?}"` where
/// the alert Debug repr is the variant name (e.g. "HandshakeFailure").
fn parse_alert_from_error(error: &str) -> (Option<u8>, Option<String>) {
    const PREFIX: &str = "received fatal alert: ";
    let Some(alert_name) = error.strip_prefix(PREFIX) else {
        return (None, None);
    };
    let alert_name = alert_name.trim();
    let code = match alert_name {
        "CloseNotify" => 0x00,
        "UnexpectedMessage" => 0x0a,
        "BadRecordMac" => 0x14,
        "DecryptionFailed" => 0x15,
        "RecordOverflow" => 0x16,
        "DecompressionFailure" => 0x1e,
        "HandshakeFailure" => 0x28,
        "NoCertificate" => 0x29,
        "BadCertificate" => 0x2a,
        "UnsupportedCertificate" => 0x2b,
        "CertificateRevoked" => 0x2c,
        "CertificateExpired" => 0x2d,
        "CertificateUnknown" => 0x2e,
        "IllegalParameter" => 0x2f,
        "UnknownCA" => 0x30,
        "AccessDenied" => 0x31,
        "DecodeError" => 0x32,
        "DecryptError" => 0x33,
        "ExportRestriction" => 0x3c,
        "ProtocolVersion" => 0x46,
        "InsufficientSecurity" => 0x47,
        "InternalError" => 0x50,
        "InappropriateFallback" => 0x56,
        "UserCanceled" => 0x5a,
        "NoRenegotiation" => 0x64,
        "MissingExtension" => 0x6d,
        "UnsupportedExtension" => 0x6e,
        "UnrecognisedName" => 0x70,
        "BadCertificateStatusResponse" => 0x71,
        "UnknownPSKIdentity" => 0x73,
        "CertificateRequired" => 0x74,
        "NoApplicationProtocol" => 0x78,
        "EncryptedClientHelloRequired" => 0x79,
        _ => return (None, Some(alert_name.to_string())),
    };
    (Some(code), Some(alert_name.to_string()))
}

/// Map a TLS alert code (with optional timing) to a DPI firmware signature.
///
/// Common patterns observed in Russian TSPU equipment:
/// - Alert 0x28 (`HandshakeFailure`) with fast timing (<200ms) indicates a
///   generic TSPU block injecting a TLS alert before the real server responds.
/// - Alert 0x46 (`ProtocolVersion`) suggests the TSPU is performing a version
///   downgrade attack, rejecting modern TLS versions.
/// - Alert 0x70 (`UnrecognisedName`) indicates SNI-based blocking where the
///   DPI device inspects the SNI extension and rejects blacklisted hostnames.
fn classify_tls_dpi_signature(alert_code: u8, tls_handshake_ms: Option<u64>) -> Option<String> {
    let fast_timing = tls_handshake_ms.is_some_and(|ms| ms < 200);
    match alert_code {
        0x28 if fast_timing => Some("tspu_generic_block".to_string()),
        0x28 => Some("handshake_failure_slow".to_string()),
        0x46 => Some("tspu_version_downgrade".to_string()),
        0x70 => Some("sni_based_block".to_string()),
        0x56 => Some("middlebox_fallback_reject".to_string()),
        _ => None,
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

/// Returns true if a TLS version split is caused by the server rejecting
/// one TLS version (protocol alert), not by network interference (timeout/reset).
/// Server-side rejections indicate the server simply doesn't support that version,
/// not that an ISP is selectively blocking it.
pub(crate) fn is_server_tls_version_rejection(tls13: &TlsObservation, tls12: &TlsObservation) -> bool {
    let failed = if tls13.status != "tls_ok" { tls13 } else { tls12 };
    let error = failed.error.as_deref().unwrap_or("");
    error.contains("AlertReceived")
        || error.contains("protocol_version")
        || error.contains("handshake_failure")
        || error.contains("inappropriate_fallback")
        || error.contains("InappropriateHandshakeMessage")
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
            ech_bootstrap_policy: None,
            ech_bootstrap_resolver_id: None,
            ech_outer_extension_policy: None,
            ech_first_flight_plan: None,
            tcp_connect_ms: None,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            observed_server_ttl: None,
            estimated_hop_count: None,
            ja3_fingerprint: None,
            tls_alert_code: None,
            tls_alert_description: None,
            tls_server_hello_received: None,
            tls_dpi_signature: None,
            connected_addr: None,
            cdn_provider: None,
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
        let config = build_standard_client_config(TlsClientProfile::Tls13Only, None);
        assert_eq!(config.alpn_protocols, vec![b"h2".to_vec(), b"http/1.1".to_vec()]);
    }

    #[test]
    fn is_server_tls_version_rejection_detects_alert() {
        let ok = obs("tls_ok", false);
        let mut failed = obs("tls_handshake_failed", false);
        failed.error = Some("AlertReceived(ProtocolVersion)".to_string());
        assert!(is_server_tls_version_rejection(&failed, &ok));
        assert!(is_server_tls_version_rejection(&ok, &failed));
    }

    #[test]
    fn is_server_tls_version_rejection_false_for_timeout() {
        let ok = obs("tls_ok", false);
        let mut failed = obs("tls_handshake_failed", false);
        failed.error = Some("connection timed out".to_string());
        assert!(!is_server_tls_version_rejection(&failed, &ok));
    }

    #[test]
    fn is_server_tls_version_rejection_false_for_reset() {
        let ok = obs("tls_ok", false);
        let mut failed = obs("tls_handshake_failed", false);
        failed.error = Some("Connection reset by peer".to_string());
        assert!(!is_server_tls_version_rejection(&failed, &ok));
    }

    #[test]
    fn parse_alert_handshake_failure() {
        let (code, desc) = parse_alert_from_error("received fatal alert: HandshakeFailure");
        assert_eq!(code, Some(0x28));
        assert_eq!(desc.as_deref(), Some("HandshakeFailure"));
    }

    #[test]
    fn parse_alert_protocol_version() {
        let (code, desc) = parse_alert_from_error("received fatal alert: ProtocolVersion");
        assert_eq!(code, Some(0x46));
        assert_eq!(desc.as_deref(), Some("ProtocolVersion"));
    }

    #[test]
    fn parse_alert_unrecognised_name() {
        let (code, desc) = parse_alert_from_error("received fatal alert: UnrecognisedName");
        assert_eq!(code, Some(0x70));
        assert_eq!(desc.as_deref(), Some("UnrecognisedName"));
    }

    #[test]
    fn parse_alert_returns_none_for_non_alert_error() {
        let (code, desc) = parse_alert_from_error("connection timed out");
        assert_eq!(code, None);
        assert_eq!(desc, None);
    }

    #[test]
    fn parse_alert_unknown_variant_returns_description_only() {
        let (code, desc) = parse_alert_from_error("received fatal alert: SomeFutureAlert");
        assert_eq!(code, None);
        assert_eq!(desc.as_deref(), Some("SomeFutureAlert"));
    }

    #[test]
    fn classify_dpi_signature_handshake_failure_fast() {
        assert_eq!(classify_tls_dpi_signature(0x28, Some(50)), Some("tspu_generic_block".to_string()));
    }

    #[test]
    fn classify_dpi_signature_handshake_failure_slow() {
        assert_eq!(classify_tls_dpi_signature(0x28, Some(500)), Some("handshake_failure_slow".to_string()));
    }

    #[test]
    fn classify_dpi_signature_protocol_version() {
        assert_eq!(classify_tls_dpi_signature(0x46, None), Some("tspu_version_downgrade".to_string()));
    }

    #[test]
    fn classify_dpi_signature_sni_block() {
        assert_eq!(classify_tls_dpi_signature(0x70, None), Some("sni_based_block".to_string()));
    }

    #[test]
    fn classify_dpi_signature_unknown_code() {
        assert_eq!(classify_tls_dpi_signature(0x32, None), None);
    }
}
