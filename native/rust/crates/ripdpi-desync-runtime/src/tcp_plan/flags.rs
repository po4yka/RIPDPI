use ripdpi_config::TcpChainStep;

use crate::platform;

pub(crate) fn tcp_step_has_flag_overrides(step: &TcpChainStep) -> bool {
    step.tcp_flags_set.unwrap_or_default() != 0
        || step.tcp_flags_unset.unwrap_or_default() != 0
        || step.tcp_flags_orig_set.unwrap_or_default() != 0
        || step.tcp_flags_orig_unset.unwrap_or_default() != 0
}

pub(crate) fn step_fake_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_set.unwrap_or_default(),
        unset: step.tcp_flags_unset.unwrap_or_default(),
    }
}

pub(crate) fn step_original_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_orig_set.unwrap_or_default(),
        unset: step.tcp_flags_orig_unset.unwrap_or_default(),
    }
}
