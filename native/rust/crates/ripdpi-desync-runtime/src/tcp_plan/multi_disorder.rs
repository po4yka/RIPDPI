use std::io;
use std::net::TcpStream;

use ripdpi_config::{IpIdMode, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::DesyncPlan;

use super::flags::step_original_tcp_flags;
use crate::platform;
use crate::strategy_family::strategy_fallback_family;
use crate::transport_io::strategy_result;
use crate::types::OutboundSendError;

pub(crate) fn execute_multi_disorder_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    send_steps: &[TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
    md5sig: bool,
    ip_id_mode: Option<IpIdMode>,
) -> Result<usize, OutboundSendError> {
    let prepared = prepare_multi_disorder_tcp_plan(send_steps, plan, strategy_family)?;
    strategy_result(
        platform::send_multi_disorder_tcp(
            writer,
            &plan.tampered,
            &prepared.segments,
            config.network.default_ttl,
            config.process.protect_path.as_deref(),
            prepared.inter_segment_delay_ms,
            md5sig,
            prepared.original_flags,
            ip_id_mode,
        ),
        "write_multidisorder",
        prepared.strategy_family,
        prepared.fallback,
        0,
    )
    .map(|()| plan.tampered.len())
}

pub(crate) struct PreparedMultiDisorderTcpPlan {
    pub(crate) strategy_family: &'static str,
    pub(crate) fallback: Option<&'static str>,
    pub(crate) inter_segment_delay_ms: u32,
    pub(crate) original_flags: platform::TcpFlagOverrides,
    pub(crate) segments: Vec<platform::TcpPayloadSegment>,
}

pub(crate) fn prepare_multi_disorder_tcp_plan(
    send_steps: &[TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
) -> Result<PreparedMultiDisorderTcpPlan, OutboundSendError> {
    if send_steps.len() < 2 || send_steps.iter().any(|step| step.kind() != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid multidisorder tcp chain configuration",
        )));
    }
    if plan.steps.len() < 3 || plan.steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder requires at least three non-empty planned segments",
        )));
    }

    let mut cursor = 0usize;
    let mut segments = Vec::with_capacity(plan.steps.len());
    for step in &plan.steps {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start != cursor || end <= start || end > plan.tampered.len() {
            return Err(OutboundSendError::transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid multidisorder tcp segment bounds",
            )));
        }
        segments.push(platform::TcpPayloadSegment { start, end });
        cursor = end;
    }
    if cursor != plan.tampered.len() {
        return Err(OutboundSendError::transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder tcp plan does not cover the full payload",
        )));
    }

    let strategy_family = strategy_family.unwrap_or("multidisorder");
    let fallback = strategy_fallback_family(strategy_family);
    let inter_segment_delay_ms = send_steps.first().map_or(0, TcpChainStep::inter_segment_delay_ms);
    let original_flags = step_original_tcp_flags(send_steps.first().expect("multidisorder send step missing"));
    Ok(PreparedMultiDisorderTcpPlan { strategy_family, fallback, inter_segment_delay_ms, original_flags, segments })
}
