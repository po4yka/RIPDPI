mod tls_attempts;

use crate::tls::{ProbeStreamOptions, TlsObservation};
use crate::transport::{TransportConfig, domain_connect_targets};
use crate::types::DomainTarget;
use crate::util::now_ms;

use self::tls_attempts::collect_tls_profile_observations;

pub(super) struct HttpsObservationCollection {
    pub(super) tls13: TlsObservation,
    pub(super) tls12: TlsObservation,
    pub(super) tls_ech: TlsObservation,
    pub(super) latency_ms: u64,
    pub(super) https_port: u16,
}

pub(super) fn collect_https_observations(
    transport: &TransportConfig,
    target: &DomainTarget,
    options: &ProbeStreamOptions<'_>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) -> HttpsObservationCollection {
    let started = now_ms();
    let https_port = target.https_port.unwrap_or(443);
    let connect_targets = domain_connect_targets(target);
    let (tls13, tls12, tls_ech) =
        collect_tls_profile_observations(&connect_targets, https_port, transport, &target.host, options, should_abort);
    let latency_ms = now_ms().saturating_sub(started);

    HttpsObservationCollection { tls13, tls12, tls_ech, latency_ms, https_port }
}
