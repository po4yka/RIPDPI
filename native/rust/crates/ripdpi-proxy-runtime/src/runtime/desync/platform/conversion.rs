use ripdpi_desync_runtime::platform::TcpFlagOverrides as DesyncTcpFlagOverrides;

use ripdpi_runtime_platform as runtime_platform;

pub(crate) fn to_runtime_flags(flags: DesyncTcpFlagOverrides) -> runtime_platform::TcpFlagOverrides {
    runtime_platform::TcpFlagOverrides { set: flags.set, unset: flags.unset }
}
