use std::io;

use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
#[cfg(test)]
use ripdpi_proxy_runtime_adapter::model::session::TriggerEvent;
use ripdpi_proxy_runtime_adapter::response_triggers::{
    failure_penalizes_strategy, failure_trigger_mask, first_response_exchange_policy,
    first_response_exchange_required_with, FirstResponseExchangePolicy,
};
#[cfg(test)]
use ripdpi_proxy_runtime_adapter::response_triggers::{response_trigger_flag, response_trigger_supported};

pub(super) type RuntimeFirstResponseExchangePolicy = FirstResponseExchangePolicy;
#[cfg(test)]
pub(super) type RuntimeTriggerEvent = TriggerEvent;

pub(super) fn runtime_first_response_exchange_policy(config: &RuntimeConfig) -> RuntimeFirstResponseExchangePolicy {
    first_response_exchange_policy(config)
}

pub(super) fn runtime_first_response_exchange_required(
    policy: RuntimeFirstResponseExchangePolicy,
    trigger_supported: impl FnMut(u32) -> io::Result<bool>,
) -> io::Result<bool> {
    first_response_exchange_required_with(policy, trigger_supported)
}

pub(super) fn runtime_failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
    failure_penalizes_strategy(failure)
}

pub(super) fn runtime_failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
    failure_trigger_mask(failure)
}

#[cfg(test)]
pub(super) fn runtime_response_trigger_flag(trigger: RuntimeTriggerEvent) -> u32 {
    response_trigger_flag(trigger)
}

#[cfg(test)]
pub(super) fn runtime_response_trigger_supported(config: &RuntimeConfig, trigger: RuntimeTriggerEvent) -> bool {
    response_trigger_supported(config, trigger)
}
