use crate::tls::{
    ProbeStreamOptions, TlsClientProfile, TlsObservation, try_tls_handshake_targets_with_options_and_abort,
};
use crate::transport::{TransportConfig, domain_connect_targets};
use crate::types::DomainTarget;

pub(super) fn retry_tls13_handshake(
    transport: &TransportConfig,
    target: &DomainTarget,
    options: &ProbeStreamOptions<'_>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
    https_port: u16,
) -> TlsObservation {
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Tls13Only,
        application_protocol: options.application_protocol,
        certificate_policy: options.certificate_policy,
        key_log: options.key_log,
    };
    try_tls_handshake_targets_with_options_and_abort(
        &domain_connect_targets(target),
        https_port,
        transport,
        &target.host,
        &options,
        should_abort,
    )
}
