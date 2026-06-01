use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{
    TlsClientProfile, TlsKeyLogCallback, TlsObservation, try_tls_handshake, try_tls_handshake_with_key_log,
};
use crate::transport::{TransportConfig, domain_connect_target};
use crate::types::DomainTarget;

pub(super) fn retry_tls13_handshake(
    transport: &TransportConfig,
    target: &DomainTarget,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    https_port: u16,
) -> TlsObservation {
    match key_log {
        Some(key_log) => try_tls_handshake_with_key_log(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
            Some(key_log),
        ),
        None => try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        ),
    }
}
