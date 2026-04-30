use std::io;
use std::os::fd::{AsRawFd, RawFd};
use std::sync::Arc;

pub trait WarpSocketProtector: Send + Sync {
    fn protect_socket(&self, fd: RawFd) -> io::Result<()>;
}

impl<F> WarpSocketProtector for F
where
    F: Fn(RawFd) -> io::Result<()> + Send + Sync,
{
    fn protect_socket(&self, fd: RawFd) -> io::Result<()> {
        self(fd)
    }
}

#[derive(Clone, Default)]
pub struct WarpPlatform {
    socket_protector: Option<Arc<dyn WarpSocketProtector>>,
}

impl WarpPlatform {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_socket_protector<P>(mut self, protector: P) -> Self
    where
        P: WarpSocketProtector + 'static,
    {
        self.socket_protector = Some(Arc::new(protector));
        self
    }

    fn socket_protector(&self) -> Option<&dyn WarpSocketProtector> {
        self.socket_protector.as_deref()
    }
}

pub(crate) fn protect_socket_if_configured<T: AsRawFd>(socket: &T, platform: &WarpPlatform) {
    if let Some(protector) = platform.socket_protector() {
        let _ = protector.protect_socket(socket.as_raw_fd());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI32, Ordering};

    struct FakeFd(RawFd);

    impl AsRawFd for FakeFd {
        fn as_raw_fd(&self) -> RawFd {
            self.0
        }
    }

    #[test]
    fn warp_platform_socket_protector_is_invoked_for_socket_fd() {
        let observed_fd = Arc::new(AtomicI32::new(-1));
        let observed = Arc::clone(&observed_fd);
        let platform = WarpPlatform::new().with_socket_protector(move |fd| {
            observed.store(fd, Ordering::SeqCst);
            Ok(())
        });
        protect_socket_if_configured(&FakeFd(42), &platform);
        assert_eq!(observed_fd.load(Ordering::SeqCst), 42);
    }

    #[test]
    fn default_warp_platform_skips_socket_protection() {
        protect_socket_if_configured(&FakeFd(42), &WarpPlatform::default());
    }

    #[test]
    fn warp_platform_socket_protector_errors_are_best_effort() {
        let observed_fd = Arc::new(AtomicI32::new(-1));
        let observed = Arc::clone(&observed_fd);
        let platform = WarpPlatform::new().with_socket_protector(move |fd| {
            observed.store(fd, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "protect rejected"))
        });
        protect_socket_if_configured(&FakeFd(7), &platform);
        assert_eq!(observed_fd.load(Ordering::SeqCst), 7);
    }
}
