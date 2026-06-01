use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{
    TlsClientProfile, TlsKeyLogCallback, TlsObservation, try_tls_handshake_targets,
    try_tls_handshake_targets_with_key_log,
};
use crate::transport::{TargetAddress, TransportConfig};

pub(super) fn collect_tls_profile_observations(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> (TlsObservation, TlsObservation, TlsObservation) {
    let tls13 = try_tls_handshake_targets_with_optional_key_log(
        targets,
        port,
        transport,
        server_name,
        true,
        TlsClientProfile::Tls13Only,
        tls_verifier,
        key_log,
    );
    let tls12 = try_tls_handshake_targets_with_optional_key_log(
        targets,
        port,
        transport,
        server_name,
        true,
        TlsClientProfile::Tls12Only,
        tls_verifier,
        key_log,
    );
    let tls_ech = try_tls_handshake_targets_with_optional_key_log(
        targets,
        port,
        transport,
        server_name,
        true,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
        key_log,
    );
    (tls13, tls12, tls_ech)
}

pub(super) fn try_tls_handshake_targets_with_optional_key_log(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> TlsObservation {
    match key_log {
        Some(key_log) => try_tls_handshake_targets_with_key_log(
            targets,
            port,
            transport,
            server_name,
            verify_certificates,
            profile,
            tls_verifier,
            Some(key_log),
        ),
        None => {
            try_tls_handshake_targets(targets, port, transport, server_name, verify_certificates, profile, tls_verifier)
        }
    }
}
