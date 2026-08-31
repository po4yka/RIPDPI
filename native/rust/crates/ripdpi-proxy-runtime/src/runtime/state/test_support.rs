use super::*;

impl RuntimeState {
    #[cfg(test)]
    pub(in crate::runtime) fn test(config: RuntimeConfig) -> Self {
        Self::test_with_context(config, None)
    }
    #[cfg(test)]
    pub(in crate::runtime) fn test_with_context(
        config: RuntimeConfig,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        Self::test_with_telemetry_and_context(config, None, runtime_context)
    }
    #[cfg(test)]
    pub(in crate::runtime) fn test_with_telemetry(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    ) -> Self {
        Self::test_with_telemetry_and_context(config, telemetry, None)
    }
    #[cfg(test)]
    pub(in crate::runtime) fn test_with_control(
        config: RuntimeConfig,
        control: std::sync::Arc<EmbeddedProxyControl>,
    ) -> Self {
        Self::new(config, Some(control), std::sync::Arc::new(ripdpi_ws_tunnel::TelegramWsTransport))
    }
    #[cfg(test)]
    pub(in crate::runtime) fn test_with_telemetry_and_context(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        Self::test_full(config, telemetry, runtime_context)
    }

    #[cfg(test)]
    fn test_full(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        let ws_transport = std::sync::Arc::new(ripdpi_ws_tunnel::TelegramWsTransport);
        let handle = new_services_handle(config.clone(), telemetry.clone(), runtime_context.clone());
        let decision_engine = new_decision_engine(&handle);
        let geo_matcher = super::super::geo::load_runtime_geo_matcher(&config);
        let destination_routing = DestinationRoutingEvaluator::compile(&config.destination_routing);
        let RuntimeConfigProjection {
            listener_settings,
            handshake_settings,
            delayed_connect_settings,
            network_reprobe_settings,
            ws_tunnel_settings,
            warmup_probe_settings,
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            udp_group_settings,
            route_payload_matcher,
            udp_flow_limit,
            relay_group_settings,
            relay_first_response,
            response_failure_evidence_settings,
        } = runtime_config_projection_with_geo(&config, geo_matcher.clone());
        let RuntimeSessionProjection {
            udp_packet_parser,
            udp_payload_classifier,
            relay_host_extractor,
            first_outbound_payload_policy,
        } = runtime_session_projection(&config);
        let RuntimeDesyncProjection { tcp_desync_executor, udp_desync_planner } = runtime_desync_projection(&config);
        let RuntimeResponseProjection { first_response_exchange_policy } = runtime_response_projection(&config);
        let (selected_tls_profile, same_sni_caps) = connection_concurrency_runtime_state(runtime_context.as_ref());

        Self {
            listener_settings,
            handshake_settings,
            delayed_connect_settings,
            network_reprobe_settings,
            ws_tunnel_settings,
            warmup_probe_settings,
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            tcp_desync_executor,
            udp_group_settings,
            route_payload_matcher,
            udp_desync_planner,
            udp_flow_limit,
            udp_packet_parser,
            udp_payload_classifier,
            relay_group_settings,
            relay_host_extractor,
            relay_first_response,
            first_outbound_payload_policy,
            first_response_exchange_policy,
            response_failure_evidence_settings,
            geo_matcher,
            destination_routing,
            services: handle,
            decision_engine,
            active_clients: Arc::new(AtomicUsize::new(0)),
            active_tcp_sockets: ActiveSocketRegistry::default(),
            active_upstream_tcp_sockets: ActiveSocketRegistry::default(),
            candidate_refusal_trials: std::sync::Arc::new(CandidateRefusalTrials::default()),
            telemetry,
            runtime_context,
            control: None,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(NetworkReprobeTracker::new()),
            exit_ip_session_limiter: ExitIpSessionLimiter::new(ExitIpSessionCaps::default()),
            same_sni_profile_limiter: SameSniProfileLimiter::new(same_sni_caps),
            selected_tls_profile,
            pcap_hook: None,
            ws_transport,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }
}
