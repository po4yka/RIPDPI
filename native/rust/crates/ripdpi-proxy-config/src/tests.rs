use crate::types::{ProxyMorphPolicy, ProxySessionOverrides};
use ripdpi_config::{
    AutoTtlConfig, DETECT_CONNECT, DesyncMode, FM_DUPSID, FM_ORIG, FakeOrder, FakePacketSource, FakeSeqMode,
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, OffsetBase, OffsetExpr, OffsetProto, QuicFakeProfile, RuntimeConfig,
    TcpChainStepKind, UdpChainStepKind, WsTunnelMode,
};
use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};
use ripdpi_packets::{IS_HTTP, IS_HTTPS, IS_UDP};
use ripdpi_packets::{MH_DMIX, MH_HMIX, MH_HOSTEXTRASPACE, MH_HOSTTAB, MH_METHODEOL, MH_SPACE, MH_UNIXEOL};

use super::*;

#[path = "tests_support.rs"]
mod tests_support;

use tests_support::*;

#[path = "tests_conversion.rs"]
mod tests_conversion;
#[path = "tests_runtime.rs"]
mod tests_runtime;
