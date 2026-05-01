mod annotations;
mod circumvention;
mod common;
mod dispatch;
mod dns;
mod domain;
mod quic;
mod service;
mod strategy;
mod tcp;
mod telegram;
mod throughput;

use crate::types::{ProbeObservation, ProbeResult};

use annotations::annotate_dns_injection_pools;
pub use common::{detail_list, detail_value, transport_failure};
pub use dispatch::observation_for_probe;

pub const ENGINE_ANALYSIS_VERSION: &str = "observations_v1";

pub fn observations_for_results(results: &[ProbeResult]) -> Vec<ProbeObservation> {
    let mut observations: Vec<ProbeObservation> = results.iter().filter_map(observation_for_probe).collect();
    annotate_dns_injection_pools(&mut observations);
    observations
}

#[cfg(test)]
mod tests;
