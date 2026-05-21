//! Runtime-adaptation — IP-fragmentation capability probe.
//!
//! `probe_ip_fragmentation_capabilities` reports whether IP-fragmentation
//! desync is usable on the current device. It tries the privileged root helper
//! first and otherwise falls back to the local `ripdpi-privileged-ops` probe;
//! non-Linux targets report `IpFragmentationCapabilities::default()`. This
//! module issues no `unsafe` of its own.

use std::io;

use super::super::IpFragmentationCapabilities;
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::{root_helper, root_helper_client};

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn probe_ip_fragmentation_capabilities(protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
    if let Some(result) = root_helper::with_root_helper(root_helper_client::RootHelperClient::probe_capabilities) {
        return result;
    }

    ripdpi_privileged_ops::probe_ip_fragmentation_capabilities(protect_path)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn probe_ip_fragmentation_capabilities(_protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
    Ok(IpFragmentationCapabilities::default())
}
