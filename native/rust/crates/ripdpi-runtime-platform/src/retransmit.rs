//! OS-primitive adapter — fake-retransmit / seqovl capability detection.
//!
//! Answers "does this platform support fake retransmission / sequence
//! overlap". `supports_fake_retransmit` is a compile-time `#[cfg]` constant;
//! `seqovl_supported` memoizes a `TCP_REPAIR` capability probe per root-helper
//! registration. Surfaced through the `tcp` facade.

use std::sync::RwLock;

/// Probe result keyed by the root-helper registration it was taken under:
/// the probe asks the registered helper, else the unprivileged local path.
static SEQOVL_SUPPORTED: RwLock<Option<(u64, bool)>> = RwLock::new(None);

#[cfg(any(target_os = "linux", target_os = "android"))]
pub const fn supports_fake_retransmit() -> bool {
    true
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub const fn supports_fake_retransmit() -> bool {
    false
}

pub fn seqovl_supported() -> bool {
    cached_for_registration(&SEQOVL_SUPPORTED, root_helper_registration(), || {
        crate::ip_fragmentation::probe_ip_fragmentation_capabilities(None).is_ok_and(|caps| caps.tcp_repair)
    })
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn root_helper_registration() -> u64 {
    crate::root_helper::registration_id()
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn root_helper_registration() -> u64 {
    0
}

fn cached_for_registration(
    cache: &RwLock<Option<(u64, bool)>>,
    registration: u64,
    probe: impl FnOnce() -> bool,
) -> bool {
    if let Ok(guard) = cache.read()
        && let Some((cached_registration, supported)) = *guard
        && cached_registration == registration
    {
        return supported;
    }
    let supported = probe();
    if let Ok(mut guard) = cache.write() {
        *guard = Some((registration, supported));
    }
    supported
}

#[cfg(test)]
mod tests {
    use std::sync::RwLock;

    use super::cached_for_registration;

    #[test]
    fn seqovl_probe_is_cached_per_root_helper_registration() {
        let cache = RwLock::new(None);

        assert!(!cached_for_registration(&cache, 0, || false), "no helper: unprivileged probe");
        assert!(!cached_for_registration(&cache, 0, || unreachable!("cached for the same registration")));
        assert!(cached_for_registration(&cache, 7, || true), "a new helper registration probes again");
        assert!(!cached_for_registration(&cache, 0, || false), "after unregister the probe runs again");
    }
}
