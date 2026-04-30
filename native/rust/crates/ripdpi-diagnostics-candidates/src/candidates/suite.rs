use super::prelude::*;

pub fn build_strategy_probe_suite(suite_id: &str, base: &ProxyUiConfig) -> Result<StrategyProbeSuite, String> {
    match suite_id {
        STRATEGY_PROBE_SUITE_QUICK_V1 => Ok(StrategyProbeSuite {
            tcp_candidates: build_tcp_candidates(base),
            quic_candidates: build_quic_candidates(base),
            short_circuit_hostfake: true,
            short_circuit_quic_burst: true,
            family_failure_threshold: 2,
        }),
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(StrategyProbeSuite {
            tcp_candidates: build_full_matrix_tcp_candidates(base),
            quic_candidates: build_full_matrix_quic_candidates(base),
            short_circuit_hostfake: false,
            short_circuit_quic_burst: false,
            family_failure_threshold: 4,
        }),
        _ => Err(format!("Unsupported automatic probing suite: {suite_id}")),
    }
}

pub fn build_quic_candidates_for_suite(
    suite_id: &str,
    base_tcp: &ProxyUiConfig,
) -> Result<Vec<StrategyCandidateSpec>, String> {
    match suite_id {
        STRATEGY_PROBE_SUITE_QUICK_V1 => Ok(build_quic_candidates(base_tcp)),
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(build_full_matrix_quic_candidates(base_tcp)),
        _ => Err(format!("Unsupported automatic probing suite: {suite_id}")),
    }
}
