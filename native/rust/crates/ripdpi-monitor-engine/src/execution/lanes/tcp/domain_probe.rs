use std::sync::Arc;
use std::thread;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;
use crate::types::DomainTarget;

use super::super::http::run_http_strategy_probe;
use super::super::https::run_https_strategy_probe;

pub(super) fn probe_domain_chunk(
    chunk: &[DomainTarget],
    transport: &TransportConfig,
    spec: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> Vec<Vec<ProbeSample>> {
    let deadline = ripdpi_diagnostics_contracts::util::active_scan_io_deadline();
    thread::scope(|s| {
        chunk
            .iter()
            .map(|target| {
                let transport = transport.clone();
                s.spawn(move || {
                    ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                        vec![
                            run_http_strategy_probe(&transport, target, spec),
                            run_https_strategy_probe(&transport, target, spec, tls_verifier, key_log),
                        ]
                    })
                })
            })
            .collect::<Vec<_>>()
            .into_iter()
            .map(|handle| handle.join().unwrap_or_default())
            .collect()
    })
}
