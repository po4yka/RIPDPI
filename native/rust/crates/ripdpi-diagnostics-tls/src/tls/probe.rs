mod capture;
mod ech;
mod observation;
mod stream;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::key_log::TlsKeyLogCallback;
use super::types::{
    ApplicationProtocolPolicy, ProbeStreamError, ProbeStreamOptions, ProbeStreamResult, TlsClientProfile,
    TlsObservation,
};
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

pub fn try_tls_handshake_with_key_log(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> TlsObservation {
    try_tls_handshake_targets_with_key_log(
        std::slice::from_ref(target),
        port,
        transport,
        server_name,
        verify_certificates,
        profile,
        tls_verifier,
        key_log,
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
    try_tls_handshake_targets_with_key_log(
        targets,
        port,
        transport,
        server_name,
        verify_certificates,
        profile,
        tls_verifier,
        None,
    )
}

pub fn try_tls_handshake_targets_with_key_log(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> TlsObservation {
    let options = ProbeStreamOptions {
        verify_certificates,
        profile,
        application_protocol: ApplicationProtocolPolicy::TemplateDefault,
        tls_verifier,
        key_log,
    };
    match open_probe_stream_targets_with_options(targets, port, transport, Some(server_name), &options) {
        Ok(result) => observation::from_probe_stream_result(result, profile),
        Err(err) => observation::from_probe_error(err, profile),
    }
}

pub fn open_probe_stream_targets_with_options(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    options: &ProbeStreamOptions<'_>,
) -> Result<ProbeStreamResult, ProbeStreamError> {
    stream::open_probe_stream_targets_with_options(targets, port, transport, tls_name, options)
}
