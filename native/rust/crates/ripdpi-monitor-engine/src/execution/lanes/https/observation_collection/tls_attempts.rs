use crate::tls::{
    ProbeStreamOptions, TlsClientProfile, TlsObservation, try_tls_handshake_targets_with_options_and_abort,
};
use crate::transport::{TargetAddress, TransportConfig};

pub(super) fn collect_tls_profile_observations(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    options: &ProbeStreamOptions<'_>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) -> (TlsObservation, TlsObservation, TlsObservation) {
    let probe = |profile| {
        let options = ProbeStreamOptions {
            profile,
            application_protocol: options.application_protocol,
            certificate_policy: options.certificate_policy,
            key_log: options.key_log,
        };
        try_tls_handshake_targets_with_options_and_abort(targets, port, transport, server_name, &options, should_abort)
    };
    (probe(TlsClientProfile::Tls13Only), probe(TlsClientProfile::Tls12Only), probe(TlsClientProfile::Tls13WithEch))
}
