//! Global callback registry for VPN socket protection.
//!
//! Inspired by protolens's VTable pattern (`PacketVTable` + `thread_local!`),
//! adapted for RIPDPI's multi-threaded tokio runtime using a `static RwLock`
//! instead of thread-local storage.
//!
//! The registered callback calls platform-specific protection (e.g., Android
//! `VpnService.protect(fd)`) to ensure upstream sockets bypass the TUN device.

use std::io;
use std::os::fd::RawFd;
use std::sync::{Arc, RwLock};

/// Callback trait for protecting file descriptors from VPN routing.
///
/// Implementations call platform-specific protection to ensure upstream
/// sockets bypass the TUN device. Must be `Send + Sync` because any
/// tokio worker thread may need to protect a socket.
pub trait ProtectCallback: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;
}

static PROTECT_CB: RwLock<Option<Arc<dyn ProtectCallback>>> = RwLock::new(None);

/// Register a protect callback. Called at VPN startup.
pub fn register_protect_callback(cb: Arc<dyn ProtectCallback>) {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = Some(cb);
}

/// Unregister the protect callback. Called at VPN shutdown.
pub fn unregister_protect_callback() {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = None;
}

/// Protect a socket fd via the registered callback.
///
/// Returns `Err(NotConnected)` if no callback is registered.
pub fn protect_socket_via_callback(fd: RawFd) -> io::Result<()> {
    let guard = PROTECT_CB.read().expect("protect callback lock poisoned");
    match guard.as_ref() {
        Some(cb) => cb.protect(fd),
        None => Err(io::Error::new(io::ErrorKind::NotConnected, "VPN protect callback not registered")),
    }
}

/// Returns true if a protect callback is currently registered.
pub fn has_protect_callback() -> bool {
    PROTECT_CB.read().is_ok_and(|guard| guard.is_some())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::Mutex;

    // Tests share the global PROTECT_CB static, so they must run serially.
    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    struct TestCallback {
        last_fd: AtomicI32,
    }

    impl TestCallback {
        fn new() -> Self {
            Self { last_fd: AtomicI32::new(-1) }
        }
    }

    impl ProtectCallback for TestCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd as i32, Ordering::Relaxed);
            Ok(())
        }
    }

    #[test]
    fn no_callback_returns_error() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_protect_callback();
        let result = protect_socket_via_callback(42);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn register_and_invoke() {
        let _lock = TEST_MUTEX.lock().unwrap();
        let cb = Arc::new(TestCallback::new());
        let cb_ref = Arc::clone(&cb);
        register_protect_callback(cb);

        assert!(has_protect_callback());
        assert!(protect_socket_via_callback(99).is_ok());
        assert_eq!(cb_ref.last_fd.load(Ordering::Relaxed), 99);

        unregister_protect_callback();
        assert!(!has_protect_callback());
    }

    #[test]
    fn unregister_clears_callback() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_protect_callback(Arc::new(TestCallback::new()));
        assert!(has_protect_callback());

        unregister_protect_callback();
        assert!(!has_protect_callback());
        assert!(protect_socket_via_callback(1).is_err());
    }

    #[test]
    fn concurrent_protect_calls() {
        let _lock = TEST_MUTEX.lock().unwrap();
        let cb = Arc::new(TestCallback::new());
        register_protect_callback(cb);

        let handles: Vec<_> = (0..8)
            .map(|i| {
                std::thread::spawn(move || {
                    protect_socket_via_callback(i).unwrap();
                })
            })
            .collect();

        for handle in handles {
            handle.join().unwrap();
        }

        unregister_protect_callback();
    }
}
