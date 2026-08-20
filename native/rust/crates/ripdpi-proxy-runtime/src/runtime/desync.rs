pub(crate) use ripdpi_proxy_runtime_adapter::desync_platform::{
    DesyncSendRequest, OutboundSendError, OutboundSendOutcome, PcapHook, TcpDesyncExecutionContext, TcpDesyncExecutor,
    TcpExecutionDisposition, TcpExecutionReceipt, TcpFallbackReason, TcpOffsetMarkerBase, TcpStrategyFamily,
    TcpTerminalReason, send_tcp_desync_payload,
};
pub(crate) use ripdpi_proxy_runtime_adapter::udp_desync::{
    UdpActionExecContext, UdpDesyncAction, UdpDesyncPlanContext, UdpDesyncPlanRequest, UdpDesyncPlanner,
    UdpExecutionError, UdpExecutionOutcome, execute_udp_actions, plan_udp_actions_for_runtime,
};

use ripdpi_proxy_runtime_adapter::desync_platform::tcp_desync_executor;
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::udp_desync::udp_desync_planner;

pub(super) struct RuntimeDesyncProjection {
    pub(super) tcp_desync_executor: TcpDesyncExecutor,
    pub(super) udp_desync_planner: UdpDesyncPlanner,
}

pub(super) fn runtime_desync_projection(config: &RuntimeConfig) -> RuntimeDesyncProjection {
    RuntimeDesyncProjection {
        tcp_desync_executor: tcp_desync_executor(config),
        udp_desync_planner: udp_desync_planner(config),
    }
}
