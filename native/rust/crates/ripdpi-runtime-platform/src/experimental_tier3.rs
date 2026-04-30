//! Runtime facade for tier-3 privileged platform primitives.

use std::io;

pub use ripdpi_privileged_ops::{
    IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole, IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind,
    SynHideTcpSpec,
};

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::root_helper;

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub fn send_syn_hide_tcp(spec: SynHideTcpSpec, protect_path: Option<&str>) -> io::Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(result) = root_helper::with_root_helper(|helper| helper.send_syn_hide_tcp(spec)) {
            return result;
        }
        ripdpi_privileged_ops::send_syn_hide_tcp(spec, protect_path)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (spec, protect_path);
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub fn send_icmp_wrapped_udp(spec: &IcmpWrappedUdpSpec, protect_path: Option<&str>) -> io::Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(result) = root_helper::with_root_helper(|helper| helper.send_icmp_wrapped_udp(spec)) {
            return result;
        }
        ripdpi_privileged_ops::send_icmp_wrapped_udp(spec, protect_path)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (spec, protect_path);
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub fn recv_icmp_wrapped_udp(
    filter: IcmpWrappedUdpRecvFilter,
    protect_path: Option<&str>,
) -> io::Result<ReceivedIcmpWrappedUdp> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(result) = root_helper::with_root_helper(|helper| helper.recv_icmp_wrapped_udp(filter)) {
            return result;
        }
        ripdpi_privileged_ops::recv_icmp_wrapped_udp(filter, protect_path)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (filter, protect_path);
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}
