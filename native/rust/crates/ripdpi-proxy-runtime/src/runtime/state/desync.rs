use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn send_tcp_desync_payload(
        &self,
        writer: &mut TcpStream,
        request: DesyncSendRequest<'_>,
    ) -> Result<OutboundSendOutcome, OutboundSendError> {
        send_tcp_desync_payload(writer, self.tcp_desync_execution_context(), request)
    }
    fn tcp_desync_execution_context(&self) -> TcpDesyncExecutionContext<'_> {
        TcpDesyncExecutionContext {
            executor: &self.tcp_desync_executor,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
            ttl_unavailable: &self.ttl_unavailable,
            pcap_hook: self.pcap_hook.as_ref(),
        }
    }
    fn plan_udp_desync_actions(&self, request: UdpDesyncPlanRequest<'_>) -> io::Result<Vec<UdpDesyncAction>> {
        plan_udp_actions_for_runtime(self.udp_desync_plan_context(), request)
    }
    pub(in crate::runtime) fn plan_udp_flow_actions(
        &self,
        group_index: usize,
        payload: &[u8],
        progress: RuntimeOutboundProgress,
        host: Option<&str>,
        target: SocketAddr,
        default_ttl: u8,
    ) -> io::Result<Vec<UdpDesyncAction>> {
        self.plan_udp_desync_actions(UdpDesyncPlanRequest {
            group_index,
            payload,
            progress: progress.into_adapter(),
            host,
            target,
            default_ttl,
        })
    }
    pub(in crate::runtime) fn execute_udp_desync_actions(
        upstream: &UdpSocket,
        target: SocketAddr,
        packet_settings: RuntimeUdpPacketSettings,
        protect_path: Option<&str>,
        actions: &[UdpDesyncAction],
    ) -> io::Result<()> {
        execute_udp_actions(
            UdpActionExecContext {
                upstream,
                target,
                default_ttl: packet_settings.default_ttl,
                protect_path,
                ip_id_mode: packet_settings.ip_id_mode,
            },
            actions,
        )
    }
    fn udp_desync_plan_context(&self) -> UdpDesyncPlanContext<'_> {
        UdpDesyncPlanContext {
            planner: &self.udp_desync_planner,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
        }
    }
}
