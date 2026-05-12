use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{
    open_probe_stream, open_probe_stream_with_key_log, NoCertificateVerification, ProbeStreamResult, TlsClientProfile,
    TlsKeyLogCallback,
};
use crate::transport::{TargetAddress, TransportConfig};

pub(super) fn open_fat_header_probe_stream(
    connect_target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    tls_sni: Option<&str>,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<ProbeStreamResult, String> {
    // Diagnostic probe: explicitly skip certificate verification to detect
    // censorship-induced TLS interception (MITM middleboxes).
    let no_verify: Arc<dyn ServerCertVerifier> = Arc::new(NoCertificateVerification);
    match key_log {
        Some(key_log) => open_probe_stream_with_key_log(
            connect_target,
            port,
            transport,
            tls_sni,
            false,
            TlsClientProfile::Auto,
            Some(&no_verify),
            Some(key_log),
        ),
        None => {
            open_probe_stream(connect_target, port, transport, tls_sni, false, TlsClientProfile::Auto, Some(&no_verify))
        }
    }
}
