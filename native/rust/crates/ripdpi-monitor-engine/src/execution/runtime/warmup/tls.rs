use crate::tls::{ProbeStreamOptions, TlsClientProfile, try_tls_handshake_targets_with_options_and_abort};
use crate::transport::{TransportConfig, domain_connect_target};
use crate::types::DomainTarget;

pub(super) fn warmup_tls13(
    transport: &TransportConfig,
    target: &DomainTarget,
    options: &ProbeStreamOptions<'_>,
    port: u16,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) {
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Tls13Only,
        application_protocol: options.application_protocol,
        certificate_policy: options.certificate_policy,
        key_log: options.key_log,
    };
    try_tls_handshake_targets_with_options_and_abort(
        &[domain_connect_target(target)],
        port,
        transport,
        &target.host,
        &options,
        should_abort,
    );
}
