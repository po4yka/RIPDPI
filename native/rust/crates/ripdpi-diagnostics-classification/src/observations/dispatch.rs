use crate::types::{ProbeObservation, ProbeResult};

use super::{circumvention, dns, domain, quic, service, strategy, tcp, telegram, throughput};

type ObservationBuilder = fn(&ProbeResult) -> Option<ProbeObservation>;

const BUILDERS: &[ObservationBuilder] = &[
    dns::build_observation,
    domain::build_observation,
    tcp::build_observation,
    quic::build_observation,
    service::build_observation,
    circumvention::build_observation,
    telegram::build_observation,
    throughput::build_observation,
    strategy::build_observation,
];

pub fn observation_for_probe(result: &ProbeResult) -> Option<ProbeObservation> {
    BUILDERS.iter().find_map(|build| build(result))
}
