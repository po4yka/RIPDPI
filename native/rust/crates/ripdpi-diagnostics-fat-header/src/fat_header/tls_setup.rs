use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{
    ApplicationProtocolPolicy, NoCertificateVerification, ProbeStreamOptions, ProbeStreamResult, TlsClientProfile,
    TlsKeyLogCallback, open_probe_stream_targets_with_options,
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
    let options = ProbeStreamOptions {
        verify_certificates: false,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: Some(&no_verify),
        key_log,
    };
    open_probe_stream_targets_with_options(std::slice::from_ref(connect_target), port, transport, tls_sni, &options)
        .map_err(|err| err.to_string())
}
