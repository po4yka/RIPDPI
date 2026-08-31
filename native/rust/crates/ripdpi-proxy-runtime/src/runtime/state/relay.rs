use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn relay_group(&self, group_index: usize) -> io::Result<RuntimeRelayGroupSettings> {
        relay_group_settings_with(&self.relay_group_settings, group_index)
            .map(RuntimeRelayGroupSettings::from_adapter)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
    }
    pub(in crate::runtime) fn relay_rotation_seed(
        &self,
        group_index: usize,
    ) -> io::Result<Option<RuntimeRelayRotationSeed>> {
        tcp_rotation_seed_with(&self.relay_group_settings, group_index)
    }
    pub(in crate::runtime) fn relay_first_response_buffer_size(&self) -> usize {
        self.relay_first_response.buffer_size
    }
    pub(in crate::runtime) fn relay_first_response_boundary_tracker(
        &self,
        request: &[u8],
    ) -> RuntimeFirstResponseBoundaryTracker {
        runtime_first_response_boundary_tracker(request, self.relay_first_response)
    }
    pub(in crate::runtime) fn relay_first_response_timeout(
        &self,
        tls_partial: &RuntimeFirstResponseBoundaryTracker,
    ) -> Option<Duration> {
        first_response_timeout(self.relay_first_response, tls_partial.active())
    }
    pub(in crate::runtime) fn relay_first_response_timeout_count_limit(&self) -> i32 {
        first_response_timeout_count_limit(self.relay_first_response)
    }
    pub(in crate::runtime) fn relay_first_response_reports_timeout_failure(&self) -> bool {
        self.relay_first_response.timeout_ms != 0
    }
    pub(in crate::runtime) fn start_relay_rotation_round(
        &self,
        rotation: &mut CircularTcpRotationController,
        progress: RuntimeOutboundProgress,
        payload: &[u8],
        retrans_baseline: Option<u32>,
        host: Option<&str>,
        target: Option<SocketAddr>,
    ) {
        rotation.start_round(
            self.relay_first_response,
            progress.round,
            progress.stream_start,
            payload,
            retrans_baseline,
            host,
            target,
        );
    }
    pub(in crate::runtime) fn append_relay_rotation_request_chunk(
        &self,
        rotation: &mut CircularTcpRotationController,
        round: u32,
        payload: &[u8],
    ) {
        rotation.append_request_chunk(self.relay_first_response, round, payload);
    }
    pub(in crate::runtime) fn first_outbound_payload_buffer_size(&self) -> usize {
        self.first_outbound_payload_policy.buffer_size
    }
    pub(in crate::runtime) fn first_response_exchange_required(&self) -> io::Result<bool> {
        runtime_first_response_exchange_required(self.first_response_exchange_policy, |trigger| {
            Ok(PolicySelectionPort::supports_trigger(&self.services, trigger))
        })
    }
    pub(in crate::runtime) fn primary_tcp_strategy_family(&self, group_index: usize) -> Option<&'static str> {
        primary_tcp_strategy_family_with(&self.relay_group_settings, group_index)
    }
    pub(in crate::runtime) fn extract_relay_payload_host(&self, payload: &[u8]) -> Option<String> {
        extract_payload_host_with(&self.relay_host_extractor, payload)
    }
    pub(in crate::runtime) fn classify_relay_connection_freeze(
        timeouts: RuntimeRelayTimeouts,
    ) -> RuntimeClassifiedFailure {
        runtime_classify_relay_connection_freeze(timeouts.into_adapter())
    }
    pub(in crate::runtime) fn relay_timeouts(&self, group_index: usize) -> io::Result<RuntimeRelayTimeouts> {
        self.relay_group(group_index).map(|group| group.timeouts())
    }
}
