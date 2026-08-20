use crate::candidates::StrategyCandidateSpec;
use crate::types::StrategyProbeRouteFeature;

pub(super) fn candidate_requires_desync_execution_evidence(spec: &StrategyCandidateSpec) -> bool {
    !matches!(spec.id, "baseline_plain_direct" | "quic_disabled")
}

pub(super) fn candidate_route_features(spec: &StrategyCandidateSpec) -> Vec<StrategyProbeRouteFeature> {
    let config = &spec.config;
    let mut features = Vec::new();
    if config.upstream_relay.enabled {
        features.push(StrategyProbeRouteFeature::UpstreamRelay);
    }
    if config.warp.enabled {
        features.push(StrategyProbeRouteFeature::Warp);
    }
    if config.ws_tunnel.enabled {
        features.push(StrategyProbeRouteFeature::WebSocketTunnel);
    }
    if config.destination_routing != <_>::default() {
        features.push(StrategyProbeRouteFeature::DestinationRouting);
    }
    if config.chains.tcp_rotation.is_some() {
        features.push(StrategyProbeRouteFeature::TcpRotation);
    }
    if config.adaptive_fallback.enabled || config.adaptive_fallback.strategy_evolution {
        features.push(StrategyProbeRouteFeature::AdaptiveFallback);
    }
    if config.chains.group_activation_filter.is_some() {
        features.push(StrategyProbeRouteFeature::GroupActivationFilter);
    }
    features
}
