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
        self.destination_routing.evaluate(
            target,
            host,
            transport,
            self.geo_matcher.as_deref().map(|matcher| matcher as &dyn GeoMatcher),
        )
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
