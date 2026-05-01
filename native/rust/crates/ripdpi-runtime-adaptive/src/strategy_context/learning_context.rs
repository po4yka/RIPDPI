use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_strategy::strategy_evolver::{
    LearningAlpnClass, LearningContext, LearningTargetBucket, LearningTransportKind,
};

use super::direct_path_capability::{capability_context, direct_path_capability_for_route};
use super::host_taxonomy::hosting_family_context;
use super::payload_classification::{classify_tcp_payload, classify_udp_payload};
use super::reachability::reachability_set_context;
use super::resolver_health::resolver_health_context;

pub fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.adaptive.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
}

pub fn tcp_learning_context(
    config: &RuntimeConfig,
    runtime_context: Option<&ProxyRuntimeContext>,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(runtime_context, host, target);
    let classification = classify_tcp_payload(payload);
    LearningContext {
        network_identity: network_scope_key(config).map(ToOwned::to_owned),
        target_bucket: if host == Some("control") {
            LearningTargetBucket::Control
        } else if classification.has_ech {
            LearningTargetBucket::Ech
        } else if classification.is_tls {
            LearningTargetBucket::Tls
        } else {
            LearningTargetBucket::Generic
        },
        transport: LearningTransportKind::Tcp,
        alpn_class: if classification.is_tls { LearningAlpnClass::H2Http11 } else { LearningAlpnClass::Unknown },
        hosting_family: hosting_family_context(host),
        reachability_set: reachability_set_context(host),
        ech_capable: classification.has_ech,
        resolver_health: resolver_health_context(runtime_context),
        rooted: config.process.root_mode,
        capability_context: capability_context(capability),
        environment: config.process.environment_kind,
    }
}

pub fn udp_learning_context(
    config: &RuntimeConfig,
    runtime_context: Option<&ProxyRuntimeContext>,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(runtime_context, host, target);
    let classification = classify_udp_payload(payload);
    LearningContext {
        network_identity: network_scope_key(config).map(ToOwned::to_owned),
        target_bucket: if classification.is_quic {
            if classification.has_ech {
                LearningTargetBucket::Ech
            } else {
                LearningTargetBucket::Quic
            }
        } else {
            LearningTargetBucket::Generic
        },
        transport: if classification.is_quic { LearningTransportKind::UdpQuic } else { LearningTransportKind::Unknown },
        alpn_class: if classification.is_quic { LearningAlpnClass::H3 } else { LearningAlpnClass::Unknown },
        hosting_family: hosting_family_context(host),
        reachability_set: reachability_set_context(host),
        ech_capable: classification.has_ech,
        resolver_health: resolver_health_context(runtime_context),
        rooted: config.process.root_mode,
        capability_context: capability_context(capability),
        environment: config.process.environment_kind,
    }
}
