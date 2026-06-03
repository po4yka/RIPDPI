use std::cell::RefCell;

use super::r#trait::TcpDesyncPlatform;

#[cfg(test)]
use super::test_support::TestTcpDesyncPlatform;

thread_local! {
    static CURRENT_PLATFORM: RefCell<Option<*const dyn TcpDesyncPlatform>> = const { RefCell::new(None) };
}

pub fn with_tcp_desync_platform<P, R>(platform: &P, f: impl FnOnce() -> R) -> R
where
    P: TcpDesyncPlatform + 'static,
{
    CURRENT_PLATFORM.with(|slot| {
        let platform = platform as &dyn TcpDesyncPlatform;
        let previous = slot.replace(Some(platform as *const dyn TcpDesyncPlatform));
        let _restore = Restore(slot, previous);
        f()
    })
}

/// Runs `f` against the platform installed for the current thread.
///
/// The platform is installed for the lifetime of the synchronous closure
/// passed to [`with_tcp_desync_platform`]. Every dispatch wrapper in
/// `dispatch_wrappers` reaches this function and relies on that
/// thread-local precondition holding.
///
/// # Panics
///
/// Panics in non-test builds if no platform is installed on the current
/// thread (i.e. this is called outside a [`with_tcp_desync_platform`]
/// scope). The dispatch wrappers return `R` directly and cannot surface a
/// recoverable error without a cross-crate API break, so the precondition
/// is enforced by a panic rather than a `Result`. In test builds a default
/// [`TestTcpDesyncPlatform`] is substituted instead of panicking.
pub(crate) fn with_current<R>(f: impl FnOnce(&dyn TcpDesyncPlatform) -> R) -> R {
    CURRENT_PLATFORM.with(|slot| {
        if let Some(pointer) = *slot.borrow() {
            // SAFETY: the only writer of this slot is `with_tcp_desync_platform`,
            // which stores a `*const dyn TcpDesyncPlatform` derived from a `&P`
            // borrow (`P: 'static`) and installs a `Restore` guard that clears the
            // slot back to its previous value on drop. `with_current` runs strictly
            // inside that synchronous `f()` call, so the referent is still borrowed
            // and live; the pointer is non-null (it came from a reference) and
            // properly aligned. The reborrow is shared (`&dyn`), matching the
            // shared `&P` the pointer was derived from, so no `&`/`&mut` aliasing
            // can occur. The slot is thread-local, so no cross-thread aliasing
            // exists either.
            let platform = unsafe { &*pointer };
            return f(platform);
        }
        #[cfg(test)]
        {
            f(&TestTcpDesyncPlatform)
        }
        #[cfg(not(test))]
        {
            panic!(
                "tcp desync platform not installed: with_current called outside a \
                 with_tcp_desync_platform scope"
            );
        }
    })
}

struct Restore<'a>(&'a RefCell<Option<*const dyn TcpDesyncPlatform>>, Option<*const dyn TcpDesyncPlatform>);

impl Drop for Restore<'_> {
    fn drop(&mut self) {
        self.0.replace(self.1);
    }
}
