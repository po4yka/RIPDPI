mod platform;
mod policy;

pub(crate) use ripdpi_desync_runtime::{primary_tcp_strategy_family, OutboundSendError, PcapHook};

pub(crate) use policy::{activation_context_from_progress, send_with_group};
