use std::collections::HashMap;
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_adaptive::strategy_context::{classify_learning_payload, direct_path_capability_for_route};
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;
use ripdpi_runtime_strategy::strategy_evolver::{
    latest_global_probe_results, CapabilityContext, LearningAlpnClass, LearningContext, LearningHostingFamily,
    LearningReachabilitySet, LearningTargetBucket, LearningTransportKind, ResolverHealthClass, StrategyEvolver,
};

pub(crate) struct StrategyEvolutionResolver {
    evolver: StrategyEvolver,
    applied_probe_generations: HashMap<LearningTargetBucket, u64>,
}

impl StrategyEvolutionResolver {
    pub(crate) fn from_config(config: &RuntimeConfig) -> Self {
        let enabled = config.adaptive.strategy_evolution;
        let epsilon = config.adaptive.evolution_epsilon_permil as f64 / 1000.0;
        Self {
            evolver: StrategyEvolver::new(enabled, epsilon).with_time_knobs(
                config.adaptive.evolution_experiment_ttl_ms,
                config.adaptive.evolution_decay_half_life_ms,
                config.adaptive.evolution_cooldown_after_failures,
                config.adaptive.evolution_cooldown_ms,
            ),
            applied_probe_generations: HashMap::new(),
        }
    }

    pub(crate) fn tcp_hints(
        &mut self,
        config: &RuntimeConfig,
        runtime_context: Option<&ProxyRuntimeContext>,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> Option<AdaptivePlannerHints> {
        if !config.adaptive.strategy_evolution {
            return None;
        }
        let context = tcp_learning_context(config, runtime_context, target, host, payload);
        let bucket = context.target_bucket;
        self.evolver.set_learning_context(context);
        self.apply_latest_probe_results(bucket);
        self.evolver.peek_hints().or_else(|| self.evolver.suggest_hints())
    }

    pub(crate) fn udp_hints(
        &mut self,
        config: &RuntimeConfig,
        runtime_context: Option<&ProxyRuntimeContext>,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> Option<AdaptivePlannerHints> {
        if !config.adaptive.strategy_evolution {
            return None;
        }
        let context = udp_learning_context(config, runtime_context, target, host, payload);
        let bucket = context.target_bucket;
        self.evolver.set_learning_context(context);
        self.apply_latest_probe_results(bucket);
        self.evolver.peek_hints().or_else(|| self.evolver.suggest_hints())
    }

    pub(crate) fn record_success(&mut self, latency_ms: u64) {
        self.evolver.record_success(latency_ms);
    }

    pub(crate) fn record_failure(&mut self, class: FailureClass) {
        self.evolver.record_failure(class);
    }

    pub(crate) fn reset(&mut self) {
        self.evolver = StrategyEvolver::new(self.evolver.is_enabled(), self.evolver.epsilon());
        self.applied_probe_generations.clear();
    }

    fn apply_latest_probe_results(&mut self, bucket: LearningTargetBucket) {
        let (generation, results) = latest_global_probe_results();
        if generation == 0 || results.is_empty() {
            return;
        }
        if self.applied_probe_generations.get(&bucket).copied() == Some(generation) {
            return;
        }
        self.evolver.inject_probe_results(&results);
        self.applied_probe_generations.insert(bucket, generation);
    }
}

fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.adaptive.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
}

fn tcp_learning_context(
    config: &RuntimeConfig,
    runtime_context: Option<&ProxyRuntimeContext>,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(runtime_context, host, target);
    let classification = classify_learning_payload(TransportProtocol::Tcp, payload);
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

fn udp_learning_context(
    config: &RuntimeConfig,
    runtime_context: Option<&ProxyRuntimeContext>,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(runtime_context, host, target);
    let classification = classify_learning_payload(TransportProtocol::Udp, payload);
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

fn capability_context(capability: Option<&ProxyDirectPathCapability>) -> CapabilityContext {
    let Some(capability) = capability else {
        return CapabilityContext::Unknown;
    };
    if ripdpi_runtime_adaptive::strategy_context::capability_requires_desync_fallback(capability) {
        CapabilityContext::Degraded
    } else {
        CapabilityContext::Full
    }
}

fn resolver_health_context(runtime_context: Option<&ProxyRuntimeContext>) -> ResolverHealthClass {
    match runtime_context.and_then(|context| context.encrypted_dns.as_ref()) {
        Some(_) => ResolverHealthClass::Healthy,
        None => ResolverHealthClass::Unknown,
    }
}

fn hosting_family_context(host: Option<&str>) -> LearningHostingFamily {
    let Some(host) = host.map(str::trim).filter(|value| !value.is_empty()) else {
        return LearningHostingFamily::Unknown;
    };
    let host = host.to_ascii_lowercase();
    if host.ends_with(".workers.dev")
        || host.ends_with(".pages.dev")
        || host.contains("cloudflare")
        || host.ends_with(".cloudflare.com")
    {
        LearningHostingFamily::Cloudflare
    } else if host.ends_with(".google.com")
        || host.ends_with(".googlevideo.com")
        || host.ends_with(".googleapis.com")
        || host.ends_with(".gstatic.com")
        || host.ends_with(".youtube.com")
        || host.ends_with(".ytimg.com")
        || host.ends_with(".1e100.net")
    {
        LearningHostingFamily::Google
    } else if host.ends_with(".yandex.ru")
        || host.ends_with(".yandex.net")
        || host.ends_with(".ya.ru")
        || host.ends_with(".vk.com")
        || host.ends_with(".vk.ru")
        || host.ends_with(".mail.ru")
        || host.ends_with(".ok.ru")
        || host.ends_with(".rutube.ru")
    {
        LearningHostingFamily::DomesticCdn
    } else if host.ends_with(".cdn77.org")
        || host.ends_with(".akamai.net")
        || host.ends_with(".akamaized.net")
        || host.ends_with(".fastly.net")
        || host.ends_with(".cloudfront.net")
        || host.ends_with(".edgekey.net")
        || host.contains("cdn")
    {
        LearningHostingFamily::ForeignCdn
    } else {
        LearningHostingFamily::Direct
    }
}

fn reachability_set_context(host: Option<&str>) -> LearningReachabilitySet {
    let Some(host) = host.map(str::trim).filter(|value| !value.is_empty()) else {
        return LearningReachabilitySet::Unknown;
    };
    if host.eq_ignore_ascii_case("control") {
        return LearningReachabilitySet::Control;
    }
    let host = host.to_ascii_lowercase();
    if host.ends_with(".ru") || host.ends_with(".su") || host.ends_with(".xn--p1ai") {
        LearningReachabilitySet::Domestic
    } else {
        LearningReachabilitySet::Foreign
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_runtime_strategy::strategy_evolver::{
        apply_global_probe_results, clear_global_probe_results_for_tests, ProbeResult,
    };

    #[test]
    fn hosting_family_context_identifies_known_cdn_buckets() {
        assert_eq!(hosting_family_context(Some("video.cloudflare.com")), LearningHostingFamily::Cloudflare);
        assert_eq!(hosting_family_context(Some("fonts.gstatic.com")), LearningHostingFamily::Google);
        assert_eq!(hosting_family_context(Some("portal.yandex.ru")), LearningHostingFamily::DomesticCdn);
        assert_eq!(hosting_family_context(Some("assets.fastly.net")), LearningHostingFamily::ForeignCdn);
        assert_eq!(hosting_family_context(Some("origin.example.com")), LearningHostingFamily::Direct);
    }

    #[test]
    fn reachability_set_context_identifies_domestic_and_control_hosts() {
        assert_eq!(reachability_set_context(Some("control")), LearningReachabilitySet::Control);
        assert_eq!(reachability_set_context(Some("service.gov.ru")), LearningReachabilitySet::Domestic);
        assert_eq!(reachability_set_context(Some("example.com")), LearningReachabilitySet::Foreign);
        assert_eq!(reachability_set_context(None), LearningReachabilitySet::Unknown);
    }

    #[test]
    fn tcp_hints_apply_injected_probe_results() {
        clear_global_probe_results_for_tests();
        apply_global_probe_results(&[ProbeResult::success("tls_rec_split", "youtube.com", 40)]);
        let mut config = RuntimeConfig::default();
        config.adaptive.strategy_evolution = true;
        let mut resolver = StrategyEvolutionResolver::from_config(&config);

        let hints = resolver
            .tcp_hints(
                &config,
                None,
                "203.0.113.10:443".parse().expect("target socket"),
                Some("youtube.com"),
                &[0x16, 0x03, 0x01, 0x00, 0x01, 0x01],
            )
            .expect("strategy hints");

        assert_eq!(hints.tls_record_offset_base, Some(ripdpi_config::OffsetBase::AutoHost));
        clear_global_probe_results_for_tests();
    }
}
