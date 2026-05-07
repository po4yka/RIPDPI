mod platform;
mod policy;

pub(crate) use ripdpi_proxy_runtime_adapter::desync_platform::{
    primary_tcp_strategy_family, OutboundSendError, PcapHook,
};

pub(crate) use platform::activation_context_from_progress;
pub(crate) use policy::send_with_group;
