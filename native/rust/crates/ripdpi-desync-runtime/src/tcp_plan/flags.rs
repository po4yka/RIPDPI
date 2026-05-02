use ripdpi_config::TcpChainStep;

use crate::platform;

pub(crate) fn tcp_step_has_flag_overrides(step: &TcpChainStep) -> bool {
    step.fake_flag_overrides().set.unwrap_or_default() != 0
        || step.fake_flag_overrides().unset.unwrap_or_default() != 0
        || step.original_flag_overrides().set.unwrap_or_default() != 0
        || step.original_flag_overrides().unset.unwrap_or_default() != 0
}

pub(crate) fn step_fake_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    let flags = step.fake_flag_overrides();
    platform::TcpFlagOverrides { set: flags.set.unwrap_or_default(), unset: flags.unset.unwrap_or_default() }
}

pub(crate) fn step_original_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    let flags = step.original_flag_overrides();
    platform::TcpFlagOverrides { set: flags.set.unwrap_or_default(), unset: flags.unset.unwrap_or_default() }
}
