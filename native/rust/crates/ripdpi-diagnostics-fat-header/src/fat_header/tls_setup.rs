use crate::tls::{
    ApplicationProtocolPolicy, ProbeCertificatePolicy, ProbeStreamError, ProbeStreamOptions, ProbeStreamResult,
    TlsClientProfile, TlsKeyLogCallback, open_probe_stream_targets_with_options,
};
use crate::transport::{TargetAddress, TransportConfig};

pub(super) fn open_fat_header_probe_stream(
    connect_target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    tls_sni: Option<&str>,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<ProbeStreamResult, ProbeStreamError> {
    // Diagnostic probe: explicitly skip certificate verification to detect
    // censorship-induced TLS interception (MITM middleboxes).
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        certificate_policy: ProbeCertificatePolicy::Skip,
        key_log,
    };
    open_probe_stream_targets_with_options(std::slice::from_ref(connect_target), port, transport, tls_sni, &options)
}
