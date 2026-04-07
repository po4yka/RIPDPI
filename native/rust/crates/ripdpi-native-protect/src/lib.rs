use std::io;
use std::os::fd::RawFd;
use std::sync::{Arc, RwLock};

pub trait ProtectCallback: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;
}

static PROTECT_CB: RwLock<Option<Arc<dyn ProtectCallback>>> = RwLock::new(None);

pub fn register_protect_callback(cb: Arc<dyn ProtectCallback>) {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = Some(cb);
}

pub fn unregister_protect_callback() {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = None;
}

pub fn protect_socket_via_callback(fd: RawFd) -> io::Result<()> {
    let guard = PROTECT_CB.read().expect("protect callback lock poisoned");
    match guard.as_ref() {
        Some(cb) => cb.protect(fd),
        None => Err(io::Error::new(io::ErrorKind::NotConnected, "VPN protect callback not registered")),
    }
}

pub fn has_protect_callback() -> bool {
    PROTECT_CB.read().is_ok_and(|guard| guard.is_some())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::Mutex;

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
            self.last_fd.store(fd, Ordering::Relaxed);
            Ok(())
        }
    }

    #[test]
    fn no_callback_returns_error() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();
        let result = protect_socket_via_callback(42);
        assert!(result.is_err());
        assert_eq!(result.expect_err("missing callback").kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn register_and_invoke() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        let cb = Arc::new(TestCallback::new());
        let cb_ref = Arc::clone(&cb);
        register_protect_callback(cb);

        assert!(has_protect_callback());
        assert!(protect_socket_via_callback(99).is_ok());
        assert_eq!(cb_ref.last_fd.load(Ordering::Relaxed), 99);

        unregister_protect_callback();
        assert!(!has_protect_callback());
    }
}
