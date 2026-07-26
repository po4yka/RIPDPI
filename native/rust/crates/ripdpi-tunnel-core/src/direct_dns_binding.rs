//! Generation-guarded Android underlay socket binder for direct DNS.

use std::io;
use std::num::NonZeroI64;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, PoisonError, RwLock};

pub trait DirectDnsSocketBinder: Send + Sync {
    /// Return the current atomic lease token when direct DNS is authorized,
    /// or `None` while the underlay is absent or stale.
    fn current_generation(&self) -> Option<u64>;

    fn is_current(&self, generation: u64) -> bool;

    /// Protect and bind an unconnected socket to the exact leased generation.
    fn bind_socket(&self, fd: RawFd, generation: u64) -> io::Result<()>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DirectDnsBinderGeneration(NonZeroI64);

impl DirectDnsBinderGeneration {
    #[must_use]
    pub const fn token(self) -> i64 {
        self.0.get()
    }
}

impl TryFrom<i64> for DirectDnsBinderGeneration {
    type Error = ();

    fn try_from(token: i64) -> Result<Self, Self::Error> {
        NonZeroI64::new(token).filter(|token| token.get() > 0).map(Self).ok_or(())
    }
}

struct RegisteredBinder {
    generation: DirectDnsBinderGeneration,
    binder: Arc<dyn DirectDnsSocketBinder>,
}

static BINDER: RwLock<Option<RegisteredBinder>> = RwLock::new(None);
static NEXT_GENERATION: AtomicI64 = AtomicI64::new(1);
#[cfg(test)]
pub(crate) static TEST_BINDER_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

#[cfg(test)]
pub(crate) struct TestBinderRegistration(DirectDnsBinderGeneration);

#[cfg(test)]
impl Drop for TestBinderRegistration {
    fn drop(&mut self) {
        let _ = unregister_direct_dns_socket_binder_if(self.0);
    }
}

#[cfg(test)]
pub(crate) fn register_test_direct_dns_socket_binder(binder: Arc<dyn DirectDnsSocketBinder>) -> TestBinderRegistration {
    TestBinderRegistration(register_direct_dns_socket_binder(binder).expect("test binder generation available"))
}

pub fn register_direct_dns_socket_binder(binder: Arc<dyn DirectDnsSocketBinder>) -> Option<DirectDnsBinderGeneration> {
    let generation = next_binder_generation()?;
    let mut slot = BINDER.write().unwrap_or_else(PoisonError::into_inner);
    *slot = Some(RegisteredBinder { generation, binder });
    Some(generation)
}

fn next_binder_generation() -> Option<DirectDnsBinderGeneration> {
    let mut current = NEXT_GENERATION.load(Ordering::Relaxed);
    loop {
        let (generation, next) = binder_generation_step(current)?;
        match NEXT_GENERATION.compare_exchange_weak(current, next, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(_) => return Some(generation),
            Err(observed) => current = observed,
        }
    }
}

fn binder_generation_step(current: i64) -> Option<(DirectDnsBinderGeneration, i64)> {
    let generation = DirectDnsBinderGeneration::try_from(current).ok()?;
    Some((generation, current.checked_add(1)?))
}

pub fn unregister_direct_dns_socket_binder_if(generation: DirectDnsBinderGeneration) -> bool {
    let mut slot = BINDER.write().unwrap_or_else(PoisonError::into_inner);
    if slot.as_ref().is_some_and(|registered| registered.generation == generation) {
        *slot = None;
        true
    } else {
        false
    }
}

fn snapshot() -> Option<(DirectDnsBinderGeneration, Arc<dyn DirectDnsSocketBinder>)> {
    BINDER
        .read()
        .unwrap_or_else(PoisonError::into_inner)
        .as_ref()
        .map(|entry| (entry.generation, Arc::clone(&entry.binder)))
}

fn registration_is_current(generation: DirectDnsBinderGeneration) -> bool {
    BINDER.read().unwrap_or_else(PoisonError::into_inner).as_ref().is_some_and(|entry| entry.generation == generation)
}

pub(crate) fn current_direct_dns_generation() -> Option<u64> {
    let (registration, binder) = snapshot()?;
    let generation = binder.current_generation().filter(|generation| *generation > 0)?;
    registration_is_current(registration).then_some(generation)
}

pub(crate) fn is_direct_dns_generation_current(generation: u64) -> bool {
    let Some((registration, binder)) = snapshot() else { return false };
    binder.is_current(generation) && registration_is_current(registration)
}

pub(crate) fn bind_direct_dns_socket(fd: RawFd, generation: u64) -> io::Result<()> {
    let (registration, binder) = snapshot()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "direct DNS underlay binder unavailable"))?;
    binder.bind_socket(fd, generation)?;
    if registration_is_current(registration) && binder.is_current(generation) {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::NotConnected, "direct DNS underlay generation changed"))
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Barrier;

    use super::*;

    struct Fixed(Option<u64>);
    impl DirectDnsSocketBinder for Fixed {
        fn current_generation(&self) -> Option<u64> {
            self.0
        }
        fn is_current(&self, generation: u64) -> bool {
            self.0 == Some(generation)
        }
        fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
            if self.0 == Some(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
        }
    }

    #[test]
    fn stale_unregister_does_not_clear_replacement() {
        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let first = register_direct_dns_socket_binder(Arc::new(Fixed(Some(1)))).expect("first registration");
        let second = register_direct_dns_socket_binder(Arc::new(Fixed(Some(2)))).expect("second registration");
        assert!(!unregister_direct_dns_socket_binder_if(first));
        assert_eq!(current_direct_dns_generation(), Some(2));
        assert!(unregister_direct_dns_socket_binder_if(second));
    }

    #[test]
    fn registration_token_rejects_non_positive_jlong_values() {
        assert!(DirectDnsBinderGeneration::try_from(-1_i64).is_err());
        assert!(DirectDnsBinderGeneration::try_from(0_i64).is_err());
        assert_eq!(DirectDnsBinderGeneration::try_from(1_i64).expect("positive token").token(), 1);
        assert!(binder_generation_step(i64::MAX).is_none(), "counter exhaustion must fail closed");
    }

    struct BlockingCurrent {
        generation: u64,
        entered: Arc<Barrier>,
        release: Arc<Barrier>,
    }

    impl DirectDnsSocketBinder for BlockingCurrent {
        fn current_generation(&self) -> Option<u64> {
            self.entered.wait();
            self.release.wait();
            Some(self.generation)
        }

        fn is_current(&self, generation: u64) -> bool {
            generation == self.generation
        }

        fn bind_socket(&self, _fd: RawFd, _generation: u64) -> io::Result<()> {
            Ok(())
        }
    }

    struct BlockingBind {
        generation: u64,
        entered: Arc<Barrier>,
        release: Arc<Barrier>,
    }

    impl DirectDnsSocketBinder for BlockingBind {
        fn current_generation(&self) -> Option<u64> {
            Some(self.generation)
        }

        fn is_current(&self, generation: u64) -> bool {
            generation == self.generation
        }

        fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
            self.entered.wait();
            self.release.wait();
            if self.is_current(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
        }
    }

    #[test]
    fn replacement_rejects_in_flight_generation_and_bind_results() {
        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let entered = Arc::new(Barrier::new(2));
        let release = Arc::new(Barrier::new(2));
        let old = register_direct_dns_socket_binder(Arc::new(BlockingCurrent {
            generation: 11,
            entered: Arc::clone(&entered),
            release: Arc::clone(&release),
        }))
        .expect("old registration");
        let call = std::thread::spawn(current_direct_dns_generation);
        entered.wait();
        let replacement =
            register_direct_dns_socket_binder(Arc::new(Fixed(Some(12)))).expect("replacement registration");
        release.wait();
        assert_eq!(call.join().expect("current-generation call"), None);
        assert!(!unregister_direct_dns_socket_binder_if(old));
        assert_eq!(current_direct_dns_generation(), Some(12));
        assert!(unregister_direct_dns_socket_binder_if(replacement));

        let entered = Arc::new(Barrier::new(2));
        let release = Arc::new(Barrier::new(2));
        let old = register_direct_dns_socket_binder(Arc::new(BlockingBind {
            generation: 21,
            entered: Arc::clone(&entered),
            release: Arc::clone(&release),
        }))
        .expect("old registration");
        let call = std::thread::spawn(|| bind_direct_dns_socket(-1, 21));
        entered.wait();
        let replacement =
            register_direct_dns_socket_binder(Arc::new(Fixed(Some(22)))).expect("replacement registration");
        release.wait();
        assert_eq!(call.join().expect("bind call").expect_err("stale bind").kind(), io::ErrorKind::NotConnected);
        assert!(!unregister_direct_dns_socket_binder_if(old));
        assert_eq!(current_direct_dns_generation(), Some(22));
        assert!(unregister_direct_dns_socket_binder_if(replacement));
    }

    #[test]
    fn zero_generation_and_binder_error_fail_closed() {
        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let registration = register_direct_dns_socket_binder(Arc::new(Fixed(Some(0)))).expect("registration");
        assert_eq!(current_direct_dns_generation(), None);
        assert_eq!(bind_direct_dns_socket(-1, 1).expect_err("wrong generation").kind(), io::ErrorKind::NotConnected);
        assert!(unregister_direct_dns_socket_binder_if(registration));
    }
}
