//! Global registry for the root helper client.
//!
//! Follows the same pattern as `protect.rs` — a global `RwLock<Option<...>>`
//! that is set at startup when root mode is enabled and the helper socket
//! path is known.

use std::sync::RwLock;

use super::root_helper_client::RootHelperClient;

static ROOT_HELPER: RwLock<Option<RootHelperClient>> = RwLock::new(None);

/// Register the root helper client. Called at service startup when
/// `root_mode = true` and the helper socket path is available.
pub fn register_root_helper(socket_path: String) {
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    *guard = Some(RootHelperClient::new(socket_path));
    tracing::info!("root helper client registered");
}

/// Unregister the root helper client. Called at service shutdown.
pub fn unregister_root_helper() {
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    *guard = None;
    tracing::info!("root helper client unregistered");
}

/// Returns `true` if a root helper client is currently registered.
pub fn has_root_helper() -> bool {
    ROOT_HELPER.read().is_ok_and(|guard| guard.is_some())
}

/// Execute a closure with a reference to the root helper client.
///
/// Returns `None` if no helper is registered.
pub fn with_root_helper<F, R>(f: F) -> Option<R>
where
    F: FnOnce(&RootHelperClient) -> R,
{
    let guard = ROOT_HELPER.read().ok()?;
    guard.as_ref().map(f)
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
        let result = with_root_helper(|client| client.socket_path());
        assert_eq!(result, Some("/tmp/test_root_helper.sock".to_string()));
        unregister_root_helper();
        assert!(!has_root_helper());
    }
}
