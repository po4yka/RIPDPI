mod capture;
mod ech;
mod observation;
mod stream;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::types::{ProbeStreamResult, TlsClientProfile, TlsObservation};
use crate::transport::{TargetAddress, TransportConfig};

pub fn try_tls_handshake(
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

pub fn try_tls_handshake_targets(
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
        Ok(result) => observation::from_probe_stream_result(result, profile),
        Err(err) => observation::from_probe_error(err, profile),
    }
}

pub fn open_probe_stream(
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

pub fn open_probe_stream_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ProbeStreamResult, String> {
    stream::open_probe_stream_targets(targets, port, transport, tls_name, verify_certificates, profile, tls_verifier)
}
