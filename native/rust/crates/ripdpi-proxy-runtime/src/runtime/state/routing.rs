use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn route_requires_delay_payload(&self, group_index: usize) -> io::Result<bool> {
        route_requires_delay_payload_with(&self.route_payload_matcher, group_index)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
    }
    pub(in crate::runtime) fn delayed_route_matches_payload(
        &self,
        group_index: usize,
        target: SocketAddr,
        payload: &[u8],
        host_hint: Option<&str>,
    ) -> bool {
        delayed_route_matches_payload_with(&self.route_payload_matcher, group_index, target, payload, host_hint)
    }
    pub(in crate::runtime) fn route_matches_transport_payload(
        &self,
        group_index: usize,
        target: SocketAddr,
        payload: &[u8],
        transport: RuntimeTransportProtocol,
    ) -> bool {
        route_matches_transport_payload_with(&self.route_payload_matcher, group_index, target, payload, transport)
    }
    pub(in crate::runtime) fn route_uses_direct_syn_data_tfo(
        &self,
        route: &RuntimeConnectionRoute,
        payload: Option<&[u8]>,
    ) -> bool {
        connection_route_requests_direct_syn_data_tfo_with(&self.route_syn_data_settings, route, payload)
    }
    pub(in crate::runtime) fn route_connect_policy(
        &self,
        group_index: usize,
        payload: Option<&[u8]>,
        allow_tfo: bool,
        egress: DestinationEgress,
    ) -> Option<RouteConnectPolicy> {
        if egress == DestinationEgress::Block {
            return None;
        }
        let settings = tcp_route_connect_settings_with(&self.route_connect_settings, group_index, payload, allow_tfo)?;
        Some(RouteConnectPolicy {
            tfo_enabled: settings.tfo_enabled,
            upstream_socks_addr: (egress == DestinationEgress::Tunneled)
                .then_some(settings.upstream_socks_addr)
                .flatten(),
            pre_connect_rcvbuf: settings.pre_connect_rcvbuf,
            connect_timeout: settings.connect_timeout,
            protect_path: settings.protect_path,
            drop_sack: settings.drop_sack,
            window_clamp: settings.window_clamp,
            strip_timestamps: settings.strip_timestamps,
        })
    }
    pub(in crate::runtime) fn destination_egress(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        transport: RuntimeTransportProtocol,
    ) -> DestinationEgress {
        let policy = self.destination_routing.evaluate(
            target,
            host,
            transport,
            self.geo_matcher.as_deref().map(|matcher| matcher as &dyn GeoMatcher),
        );
        if policy != DestinationEgress::Block
            && matches!(target.port(), 53 | 853)
            && self.awg_dns_servers.contains(&target.ip())
        {
            DestinationEgress::Tunneled
        } else {
            policy
        }
    }
    pub(in crate::runtime) fn destination_policy_may_need_host(&self) -> bool {
        self.destination_routing.is_active() && self.destination_routing.may_need_host()
    }
    pub(in crate::runtime) fn select_initial_route(
        &self,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: RuntimeTransportProtocol,
    ) -> Option<RuntimeConnectionRoute> {
        PolicySelectionPort::select_initial(
            &self.services,
            target,
            payload,
            host,
            allow_unknown_payload,
            transport,
            self.geo_matcher.as_deref().map(|matcher| matcher as &dyn GeoMatcher),
        )
    }
    #[allow(clippy::too_many_arguments)]
    pub(in crate::runtime) fn select_next_route(
        &self,
        route: &RuntimeConnectionRoute,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: RuntimeTransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RuntimeRetrySelectionPenalty>>,
    ) -> Option<RuntimeConnectionRoute> {
        PolicySelectionPort::select_next(
            &self.services,
            route,
            target,
            payload,
            host,
            transport,
            trigger,
            can_reconnect,
            retry_penalties,
            self.geo_matcher.as_deref().map(|matcher| matcher as &dyn GeoMatcher),
        )
    }
    pub(in crate::runtime) fn note_route_success(
        &self,
        target: SocketAddr,
        route: &RuntimeConnectionRoute,
        host: Option<&str>,
        transport: RuntimeTransportProtocol,
    ) -> io::Result<()> {
        PolicyLearningPort::note_success(&self.services, target, route, host, transport)
    }
    pub(in crate::runtime) fn runtime_supports_trigger(&self, trigger: u32) -> bool {
        PolicySelectionPort::supports_trigger(&self.services, trigger)
    }
    pub(in crate::runtime) fn retry_trigger_for_failure(&self, failure: &RuntimeClassifiedFailure) -> Option<u32> {
        let trigger = runtime_failure_trigger_mask(failure);
        if failure.action != RuntimeFailureAction::RetryWithMatchingGroup
            || trigger == 0
            || !self.runtime_supports_trigger(trigger)
        {
            return None;
        }
        Some(trigger)
    }
    pub(in crate::runtime) fn should_track_strategy_target(target: SocketAddr) -> bool {
        runtime_should_track_strategy_target(target)
    }
    pub(in crate::runtime) fn note_block_signal_for_failure(
        &self,
        host: Option<&str>,
        failure: &RuntimeClassifiedFailure,
        tcp_total_retransmissions: Option<u32>,
    ) {
        let Some(host) = host else {
            return;
        };
        let Some(signal) = runtime_block_signal_from_failure(failure, tcp_total_retransmissions) else {
            return;
        };
        self.note_block_signal(
            host,
            signal.signal,
            signal.provider.as_deref(),
            self.block_signal_confirmation_allowed(),
        );
    }
    pub(in crate::runtime) fn note_block_signal(
        &self,
        host: &str,
        signal: RuntimeBlockSignal,
        provider: Option<&str>,
        confirmation_allowed: bool,
    ) {
        PolicyLearningPort::note_block_signal(&self.services, host, signal, provider, confirmation_allowed);
    }
    pub(in crate::runtime) fn advance_route(
        &self,
        route: &RuntimeConnectionRoute,
        advance: RuntimeRouteAdvance<'_>,
    ) -> io::Result<Option<RuntimeConnectionRoute>> {
        PolicySelectionPort::advance_route(&self.services, route, advance)
    }
    pub(in crate::runtime) fn store_udp_route_hint(
        &self,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    ) {
        PolicySelectionPort::store_route(&self.services, dest, group_index, attempted_mask, host);
    }
}

#[cfg(test)]
mod awg_dns_tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationIpMatcher, DestinationIpMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
        DestinationRoutingPolicy, DestinationRoutingRule, UpstreamSocksConfig,
    };

    fn lan_rule(action: DestinationRoutingAction) -> DestinationRoutingRule {
        DestinationRoutingRule {
            action,
            network: DestinationRoutingNetwork::Both,
            domains: Vec::new(),
            ip_ranges: ["10.0.0.0/8", "fc00::/7"]
                .into_iter()
                .map(|value| DestinationIpMatcher { kind: DestinationIpMatcherKind::Cidr, value: value.into() })
                .collect(),
            destination_ports: Vec::new(),
        }
    }

    fn state(actions: &[DestinationRoutingAction], active: bool) -> RuntimeState {
        let mut config = RuntimeConfig {
            destination_routing: DestinationRoutingPolicy {
                rules: actions.iter().copied().map(lan_rule).collect(),
                ..DestinationRoutingPolicy::default()
            },
            awg_dns_servers: if active {
                vec!["10.77.0.1".parse().unwrap(), "fd77::1".parse().unwrap()]
            } else {
                Vec::new()
            },
            ..RuntimeConfig::default()
        };
        config.groups[0].policy.ext_socks =
            Some(UpstreamSocksConfig { addr: "127.0.0.1:10809".parse().unwrap(), auth: None });
        RuntimeState::test(config)
    }

    #[test]
    fn awg_resolver_ports_keep_tcp_and_udp_upstreams_despite_lan_direct() {
        let state = state(&[DestinationRoutingAction::Direct], true);
        for ip in ["10.77.0.1", "fd77::1"] {
            for port in [53, 853] {
                for transport in [RuntimeTransportProtocol::Tcp, RuntimeTransportProtocol::Udp] {
                    let target = SocketAddr::new(ip.parse().unwrap(), port);
                    let egress = state.destination_egress(target, None, transport);
                    assert_eq!(egress, DestinationEgress::Tunneled);
                    let expected = Some("127.0.0.1:10809".parse().unwrap());
                    assert_eq!(
                        state.route_connect_policy(0, None, false, egress).unwrap().upstream_socks_addr,
                        expected
                    );
                    assert_eq!(state.udp_flow_group_policy(0, egress).unwrap().upstream_socks_addr, expected);
                }
            }
        }
    }

    #[test]
    fn unrelated_lan_and_inactive_resolvers_keep_direct_routing() {
        for active in [true, false] {
            let state = state(&[DestinationRoutingAction::Direct], active);
            let targets = if active {
                vec!["10.77.0.1:443", "[fd77::1]:443", "10.77.0.2:53", "[fd77::2]:853"]
            } else {
                vec!["10.77.0.1:53", "[fd77::1]:853"]
            };
            for target in targets {
                for transport in [RuntimeTransportProtocol::Tcp, RuntimeTransportProtocol::Udp] {
                    let egress = state.destination_egress(target.parse().unwrap(), None, transport);
                    assert_eq!(egress, DestinationEgress::Direct);
                    assert!(state.route_connect_policy(0, None, false, egress).unwrap().upstream_socks_addr.is_none());
                    assert!(state.udp_flow_group_policy(0, egress).unwrap().upstream_socks_addr.is_none());
                }
            }
        }
    }

    #[test]
    fn resolver_override_preserves_effective_block_and_first_match_order() {
        use DestinationRoutingAction::{Block, Direct};
        for (actions, expected) in
            [(vec![Block, Direct], DestinationEgress::Block), (vec![Direct, Block], DestinationEgress::Tunneled)]
        {
            let state = state(&actions, true);
            for target in ["10.77.0.1:53", "[fd77::1]:853"] {
                for transport in [RuntimeTransportProtocol::Tcp, RuntimeTransportProtocol::Udp] {
                    let egress = state.destination_egress(target.parse().unwrap(), None, transport);
                    assert_eq!(egress, expected);
                    if egress == DestinationEgress::Block {
                        assert!(state.route_connect_policy(0, None, false, egress).is_none());
                        assert!(state.udp_flow_group_policy(0, egress).is_none());
                    }
                }
            }
        }
    }
}
