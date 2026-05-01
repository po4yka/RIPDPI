use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{open_probe_stream, NoCertificateVerification, ProbeStreamResult, TlsClientProfile};
use crate::transport::{TargetAddress, TransportConfig};

pub(super) fn open_fat_header_probe_stream(
    connect_target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    tls_sni: Option<&str>,
) -> Result<ProbeStreamResult, String> {
    // Diagnostic probe: explicitly skip certificate verification to detect
    // censorship-induced TLS interception (MITM middleboxes).
    let no_verify: Arc<dyn ServerCertVerifier> = Arc::new(NoCertificateVerification);
    open_probe_stream(connect_target, port, transport, tls_sni, false, TlsClientProfile::Auto, Some(&no_verify))
}
