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

pub(crate) fn with_current<R>(f: impl FnOnce(&dyn TcpDesyncPlatform) -> R) -> R {
    CURRENT_PLATFORM.with(|slot| {
        if let Some(pointer) = *slot.borrow() {
            // SAFETY: `with_tcp_desync_platform` installs a pointer that is valid
            // for the duration of the synchronous execution closure.
            let platform = unsafe { &*pointer };
            return f(platform);
        }
        #[cfg(test)]
        {
            f(&TestTcpDesyncPlatform)
        }
        #[cfg(not(test))]
        {
            panic!("tcp desync platform not installed");
        }
    })
}

struct Restore<'a>(&'a RefCell<Option<*const dyn TcpDesyncPlatform>>, Option<*const dyn TcpDesyncPlatform>);

impl Drop for Restore<'_> {
    fn drop(&mut self) {
        self.0.replace(self.1);
    }
}
