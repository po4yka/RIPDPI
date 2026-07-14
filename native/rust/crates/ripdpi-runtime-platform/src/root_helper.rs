//! Runtime-adaptation — process-global registry for the root helper client.
//!
//! Follows the same pattern as `protect.rs` — a global `RwLock<Option<...>>`
//! that is set at startup when root mode is enabled and the helper socket
//! path is known. The other runtime-adaptation modules (`fake_send`,
//! `ip_fragmentation`, `experimental_tier3`) read this slot through
//! [`with_root_helper`] to decide between the privileged and local paths.
//! Linux/Android only.
//!
//! ## Generation guard
//!
//! Registration is process-global, so an asymmetric register/unregister can
//! let a stale `unregister` from an ended session clear a *newer* session's
//! client (a "stale unregister"). Each [`register_root_helper_versioned`] call
//! stamps the slot with a monotonic [`RootHelperGeneration`];
//! [`unregister_root_helper_if`] clears the slot only when its generation
//! still matches, so a late unregister from a superseded session becomes a
//! no-op instead of clobbering the live registration.
//!
//! RAII guards should register via [`register_root_helper_versioned`], keep
//! the returned generation, and release through [`unregister_root_helper_if`].
//! [`register_root_helper`] / [`unregister_root_helper`] remain as
//! unconditional back-compat wrappers for callers that do not race a later
//! session (single active session — the common case — is unaffected: the
//! generation increments and always matches on release).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};

use super::root_helper_client::RootHelperClient;

/// Monotonic token identifying one [`register_root_helper_versioned`] call.
///
/// Held by the registering RAII guard and passed back to
/// [`unregister_root_helper_if`] so a stale unregister cannot clear a slot a
/// later session re-registered. Opaque: only equality against the value the
/// registry handed out is meaningful.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RootHelperGeneration(u64);

/// The registered client plus the generation that registered it.
struct RegisteredRootHelper {
    generation: RootHelperGeneration,
    client: Arc<RootHelperClient>,
}

static ROOT_HELPER: RwLock<Option<RegisteredRootHelper>> = RwLock::new(None);
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

/// Register the root helper client and return the [`RootHelperGeneration`]
/// stamped on the registry slot.
///
/// Prefer this over [`register_root_helper`] for RAII guards: keep the
/// returned generation and release through [`unregister_root_helper_if`] so a
/// stale unregister cannot clobber a newer session's registration.
pub fn register_root_helper_versioned(socket_path: String) -> RootHelperGeneration {
    let generation = RootHelperGeneration(NEXT_GENERATION.fetch_add(1, Ordering::Relaxed));
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    *guard = Some(RegisteredRootHelper { generation, client: Arc::new(RootHelperClient::new(socket_path)) });
    tracing::info!(generation = generation.0, "root helper client registered");
    generation
}

/// Register the root helper client. Called at service startup when
/// `root_mode = true` and the helper socket path is available.
///
/// Unconditional back-compat wrapper over [`register_root_helper_versioned`]
/// that discards the generation. Callers that may race a later session should
/// use the versioned form together with [`unregister_root_helper_if`].
pub fn register_root_helper(socket_path: String) {
    let _ = register_root_helper_versioned(socket_path);
}

/// Unregister the root helper client. Called at service shutdown.
///
/// Unconditional: clears whatever is registered. Prefer
/// [`unregister_root_helper_if`] when a stale unregister could otherwise clear
/// a newer session's client.
pub fn unregister_root_helper() {
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    *guard = None;
    tracing::info!("root helper client unregistered");
}

/// Unregister the root helper client only if the slot still carries
/// `generation`.
///
/// Returns `true` when the slot was cleared, `false` when a newer
/// `register_root_helper*` call has already superseded this generation — the
/// stale unregister is then ignored and logged. This is the safe release path
/// for RAII guards.
pub fn unregister_root_helper_if(generation: RootHelperGeneration) -> bool {
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    match guard.as_ref() {
        Some(registered) if registered.generation == generation => {
            *guard = None;
            tracing::info!(generation = generation.0, "root helper client unregistered");
            true
        }
        Some(registered) => {
            tracing::warn!(
                stale = generation.0,
                current = registered.generation.0,
                "ignored stale root helper unregister (superseded by a newer session)"
            );
            false
        }
        None => false,
    }
}

/// Returns `true` if a root helper client is currently registered.
pub fn has_root_helper() -> bool {
    ROOT_HELPER.read().is_ok_and(|guard| guard.is_some())
}

/// Execute a closure with a reference to the root helper client.
///
/// Returns `None` if no helper is registered. The registry lock is released
/// before `f` runs; an `Arc` snapshot keeps an in-flight client alive across a
/// concurrent unregister without blocking registry lifecycle operations.
pub fn with_root_helper<F, R>(f: F) -> Option<R>
where
    F: FnOnce(&RootHelperClient) -> R,
{
    let client = {
        let guard = ROOT_HELPER.read().ok()?;
        Arc::clone(&guard.as_ref()?.client)
    };
    Some(f(client.as_ref()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    #[test]
    fn no_helper_returns_none() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_root_helper();
        assert!(!has_root_helper());
        let result = with_root_helper(|_| 42);
        assert!(result.is_none());
    }

    #[test]
    fn register_and_check() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_root_helper("/tmp/test_root_helper.sock".into());
        assert!(has_root_helper());
        let result = with_root_helper(super::super::root_helper_client::RootHelperClient::socket_path);
        assert_eq!(result, Some("/tmp/test_root_helper.sock".to_string()));
        unregister_root_helper();
        assert!(!has_root_helper());
    }

    #[test]
    fn callback_runs_without_holding_registry_lock() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_root_helper("/tmp/test_root_helper.sock".into());

        let lock_available = with_root_helper(|_| ROOT_HELPER.try_write().is_ok());

        assert_eq!(lock_available, Some(true));
        unregister_root_helper();
    }

    #[test]
    fn versioned_register_hands_out_distinct_generations() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_root_helper();
        let first = register_root_helper_versioned("/tmp/first.sock".into());
        let second = register_root_helper_versioned("/tmp/second.sock".into());
        assert_ne!(first, second);
        unregister_root_helper();
    }

    #[test]
    fn stale_unregister_is_ignored_after_a_newer_register() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_root_helper();

        let stale = register_root_helper_versioned("/tmp/old-session.sock".into());
        let current = register_root_helper_versioned("/tmp/new-session.sock".into());

        // The stale generation no longer matches the slot -> unregister is a no-op
        // and the newer session's client survives.
        assert!(!unregister_root_helper_if(stale));
        assert!(has_root_helper());
        assert_eq!(with_root_helper(RootHelperClient::socket_path), Some("/tmp/new-session.sock".to_string()));

        // The current generation matches and clears the slot.
        assert!(unregister_root_helper_if(current));
        assert!(!has_root_helper());

        // Releasing an already-cleared generation is a harmless no-op.
        assert!(!unregister_root_helper_if(current));
    }
}
