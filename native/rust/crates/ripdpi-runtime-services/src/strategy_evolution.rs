use std::collections::HashSet;
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_adaptive::strategy_context::{classify_learning_payload, direct_path_capability_for_route};
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;
use ripdpi_runtime_strategy::strategy_evolver::{
    CapabilityContext, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass, StrategyEvolver, latest_global_probe_results,
};

pub(crate) struct StrategyEvolutionResolver {
    evolver: StrategyEvolver,
    current_probe_generation: u64,
    consumed_probe_domains: HashSet<(LearningTargetBucket, String)>,
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
            current_probe_generation: 0,
            consumed_probe_domains: HashSet::new(),
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
        self.apply_latest_probe_results(bucket, host);
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
        self.apply_latest_probe_results(bucket, host);
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
        self.current_probe_generation = 0;
        self.consumed_probe_domains.clear();
    }

    fn apply_latest_probe_results(&mut self, bucket: LearningTargetBucket, host: Option<&str>) {
        let (generation, results) = latest_global_probe_results();
        if generation == 0 {
            return;
        }
        if generation != self.current_probe_generation {
            self.current_probe_generation = generation;
            self.consumed_probe_domains.clear();
        }
        let Some(domain) = host.and_then(normalize_probe_domain).map(str::to_ascii_lowercase) else {
            return;
        };
        let key = (bucket, domain);
        if self.consumed_probe_domains.contains(&key) {
            return;
        }
        let matching_results = results
            .into_iter()
            .filter(|result| {
                normalize_probe_domain(&result.domain)
                    .is_some_and(|result_domain| result_domain.eq_ignore_ascii_case(&key.1))
            })
            .collect::<Vec<_>>();
        if matching_results.is_empty() {
            return;
        }
        self.evolver.inject_probe_results(&matching_results);
        self.consumed_probe_domains.insert(key);
    }
}

fn normalize_probe_domain(domain: &str) -> Option<&str> {
    let domain = domain.trim().trim_end_matches('.');
    (!domain.is_empty()).then_some(domain)
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
            if classification.has_ech { LearningTargetBucket::Ech } else { LearningTargetBucket::Quic }
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
        PROBE_OBSERVATION_WEIGHT, ProbeResult, apply_global_probe_results, clear_global_probe_results_for_tests,
        probe_combo_for_strategy_id,
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
        apply_global_probe_results(&[
            ProbeResult::success("tls_rec_split", "YouTube.COM.", 40),
            ProbeResult::success("split", "discord.com", 25),
        ]);
        let mut config = RuntimeConfig::default();
        config.adaptive.strategy_evolution = true;
        config.adaptive.evolution_epsilon_permil = 0;
        let mut resolver = StrategyEvolutionResolver::from_config(&config);
        let target = "203.0.113.10:443".parse().expect("target socket");
        let tls_record_combo = probe_combo_for_strategy_id("tls_rec_split").expect("TLS record strategy combo");
        let split_combo = probe_combo_for_strategy_id("split").expect("split strategy combo");
        let oob_combo = probe_combo_for_strategy_id("oob").expect("OOB strategy combo");

        resolver
            .tcp_hints(&config, None, target, Some("unprobed.example"), &minimal_tls_client_hello())
            .expect("strategy hints for unprobed host");

        assert!(resolver.evolver.combo_stats_for(&tls_record_combo).is_none());
        assert!(resolver.evolver.combo_stats_for(&split_combo).is_none());

        resolver
            .tcp_hints(&config, None, target, Some("youtube.com"), &minimal_tls_client_hello())
            .expect("strategy hints for YouTube");
        assert_eq!(
            resolver.evolver.combo_stats_for(&tls_record_combo).expect("TLS record strategy stats").attempts,
            PROBE_OBSERVATION_WEIGHT
        );
        assert!(resolver.evolver.combo_stats_for(&split_combo).is_none());

        resolver
            .tcp_hints(&config, None, target, Some("YOUTUBE.COM."), &minimal_tls_client_hello())
            .expect("strategy hints for repeated YouTube host");
        assert_eq!(
            resolver.evolver.combo_stats_for(&tls_record_combo).expect("TLS record strategy stats").attempts,
            PROBE_OBSERVATION_WEIGHT
        );

        resolver
            .tcp_hints(&config, None, target, Some("Discord.COM."), &minimal_tls_client_hello())
            .expect("strategy hints for Discord");
        assert_eq!(
            resolver.evolver.combo_stats_for(&split_combo).expect("split strategy stats").attempts,
            PROBE_OBSERVATION_WEIGHT
        );

        apply_global_probe_results(&[ProbeResult::success("oob", "youtube.com", 20)]);
        resolver
            .tcp_hints(&config, None, target, Some("YouTube.COM."), &minimal_tls_client_hello())
            .expect("strategy hints for new YouTube generation");
        assert_eq!(
            resolver.evolver.combo_stats_for(&oob_combo).expect("OOB strategy stats").attempts,
            PROBE_OBSERVATION_WEIGHT
        );
        assert_eq!(
            resolver.evolver.combo_stats_for(&tls_record_combo).expect("TLS record strategy stats").attempts,
            PROBE_OBSERVATION_WEIGHT
        );
        assert_eq!(
            resolver.evolver.combo_stats_for(&split_combo).expect("split strategy stats").attempts,
            PROBE_OBSERVATION_WEIGHT
        );
        clear_global_probe_results_for_tests();
    }

    fn minimal_tls_client_hello() -> Vec<u8> {
        let sni = b"youtube.com";
        let sni_len = sni.len();
        let sni_ext_len = 2 + 1 + 2 + sni_len;
        let ext_total_len = 2 + 2 + sni_ext_len;
        let handshake_len = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + ext_total_len;
        let mut payload = Vec::new();
        payload.push(0x16);
        payload.extend([0x03, 0x01]);
        payload.extend(((handshake_len + 4) as u16).to_be_bytes());
        payload.push(0x01);
        payload.push(0x00);
        payload.extend((handshake_len as u16).to_be_bytes());
        payload.extend([0x03, 0x03]);
        payload.extend([0u8; 32]);
        payload.push(0x00);
        payload.extend(2u16.to_be_bytes());
        payload.extend([0x00, 0x2f]);
        payload.push(0x01);
        payload.push(0x00);
        payload.extend((ext_total_len as u16).to_be_bytes());
        payload.extend(0u16.to_be_bytes());
        payload.extend((sni_ext_len as u16).to_be_bytes());
        payload.extend(((sni_ext_len - 2) as u16).to_be_bytes());
        payload.push(0x00);
        payload.extend((sni_len as u16).to_be_bytes());
        payload.extend(sni);
        payload
    }
}
