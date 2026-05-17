use ripdpi_desync_runtime::platform::TcpFlagOverrides as DesyncTcpFlagOverrides;

use crate::platform as runtime_platform;

pub fn to_runtime_flags(flags: DesyncTcpFlagOverrides) -> runtime_platform::raw_packet::TcpFlagOverrides {
    runtime_platform::raw_packet::TcpFlagOverrides { set: flags.set, unset: flags.unset }
}
