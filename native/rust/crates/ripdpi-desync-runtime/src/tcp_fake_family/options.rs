use std::time::Duration;

use ripdpi_config::{FakeOrder, FakeSeqMode, TcpChainStep};

use crate::platform;
use crate::tcp_plan::{step_fake_tcp_flags, step_original_tcp_flags};

use super::TcpFakeFamilyExecContext;

#[derive(Clone, Copy)]
pub(crate) struct FakeStepOptions {
    pub(crate) fake_ttl: u8,
    pub(crate) fake_flags: platform::TcpFlagOverrides,
    pub(crate) original_flags: platform::TcpFlagOverrides,
    pub(crate) timestamp_delta_ticks: Option<i32>,
    pub(crate) wait: (bool, Duration),
    pub(crate) custom_order: bool,
}

impl FakeStepOptions {
    pub(crate) fn new(ctx: &TcpFakeFamilyExecContext<'_>, configured_step: &TcpChainStep) -> Self {
        Self {
            fake_ttl: ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8),
            fake_flags: step_fake_tcp_flags(configured_step),
            original_flags: step_original_tcp_flags(configured_step),
            timestamp_delta_ticks: ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks),
            wait: (
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            ),
            custom_order: configured_step.fake_ordering().order != FakeOrder::BeforeEach
                || configured_step.fake_ordering().seq_mode != FakeSeqMode::Duplicate,
        }
    }

    pub(crate) fn tcp_options<'a>(
        self,
        protect_path: Option<&'a str>,
        secondary_fake_prefix: Option<&'a [u8]>,
    ) -> platform::FakeTcpOptions<'a> {
        platform::FakeTcpOptions {
            secondary_fake_prefix,
            timestamp_delta_ticks: self.timestamp_delta_ticks,
            protect_path,
            fake_flags: self.fake_flags,
            orig_flags: self.original_flags,
            ..Default::default()
        }
    }
}
