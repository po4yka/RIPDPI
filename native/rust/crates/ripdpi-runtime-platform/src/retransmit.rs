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

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use super::seqovl_supported;
    use crate::root_helper::tests::{FakeHelper, TEST_MUTEX};

    #[test]
    fn seqovl_support_is_probed_again_for_a_new_helper_registration() {
        let _lock = TEST_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner);

        // One preflight and one probe each; a cached answer makes no request.
        let repair = FakeHelper::register(serde_json::json!({ "tcp_repair": true }), 2);
        assert!(seqovl_supported(), "the helper reports TCP_REPAIR");
        assert!(seqovl_supported(), "cached for the same registration");
        assert_eq!(repair.finish().len(), 2);

        let no_repair = FakeHelper::register(serde_json::json!({ "tcp_repair": false }), 2);
        assert!(!seqovl_supported(), "a new registration must not reuse the old answer");
        assert_eq!(no_repair.finish().len(), 2);
    }
}
