use ripdpi_desync_runtime::platform::TcpFlagOverrides as DesyncTcpFlagOverrides;

use ripdpi_proxy_runtime_adapter::platform as runtime_platform;

pub(crate) fn to_runtime_flags(flags: DesyncTcpFlagOverrides) -> runtime_platform::raw_packet::TcpFlagOverrides {
    runtime_platform::raw_packet::TcpFlagOverrides { set: flags.set, unset: flags.unset }
}
