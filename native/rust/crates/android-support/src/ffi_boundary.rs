//! Panic containment for FFI boundaries.
//!
//! The `android-jni` cargo profile sets `panic = "unwind"`; unwinding across
//! an `extern "C"` / `extern "system"` boundary is undefined behaviour. Every
//! JNI export and every C callback invoked from foreign code must catch
//! panics and substitute a sentinel return value.
//!
//! `install_panic_hook` (see `logging.rs`) is responsible for *logging* the
//! panic via `log::error!` — it fires before `catch_unwind` returns control,
//! so this helper does not emit its own log line and never sees the payload.

use std::panic::{catch_unwind, AssertUnwindSafe};

/// Run `f` inside a panic boundary. If `f` panics, return `default_on_panic`
/// instead of unwinding into foreign code.
///
/// The closure is wrapped in `AssertUnwindSafe`: callers must not rely on
/// post-panic state of values captured by `f` (mutexes are auto-poisoned by
/// the std library; any other invariants are the caller's responsibility).
#[inline]
pub fn ffi_boundary<T, F>(default_on_panic: T, f: F) -> T
where
    F: FnOnce() -> T,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(value) => value,
        Err(_payload) => default_on_panic,
    }
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;
    use std::sync::Mutex;

    static PANIC_HOOK_GUARD: Mutex<()> = Mutex::new(());

    type PanicHook = Box<dyn Fn(&std::panic::PanicHookInfo<'_>) + Send + Sync + 'static>;

    /// Swap the global panic hook to a silent no-op for the duration of a test
    /// so the captured panic does not spam test output. The hook is restored
    /// when the returned guard is dropped.
    struct SilentPanicHook {
        previous: Option<PanicHook>,
        _lock: std::sync::MutexGuard<'static, ()>,
    }

    impl SilentPanicHook {
        fn install() -> Self {
            let lock = PANIC_HOOK_GUARD.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let previous = std::panic::take_hook();
            std::panic::set_hook(Box::new(|_| {}));
            SilentPanicHook { previous: Some(previous), _lock: lock }
        }
    }

    impl Drop for SilentPanicHook {
        fn drop(&mut self) {
            if let Some(previous) = self.previous.take() {
                std::panic::set_hook(previous);
            }
        }
    }

    #[test]
    fn returns_inner_value_when_no_panic() {
        assert_eq!(ffi_boundary(-1_i32, || 42_i32), 42);
    }

    #[test]
    fn substitutes_default_when_inner_panics() {
        let _silent = SilentPanicHook::install();
        let result = ffi_boundary(-1_i32, || panic!("simulated FFI panic"));
        assert_eq!(result, -1);
    }

    #[test]
    fn pointer_default_substitutes_null_on_panic() {
        let _silent = SilentPanicHook::install();
        let result: *mut u8 = ffi_boundary(core::ptr::null_mut(), || panic!("simulated FFI panic"));
        assert!(result.is_null());
    }

    #[test]
    fn unit_return_swallows_panic_without_unwinding() {
        let _silent = SilentPanicHook::install();
        // The interesting property is that this call returns normally
        // instead of unwinding the test thread.
        ffi_boundary((), || panic!("simulated FFI panic"));
    }

    #[test]
    fn captured_value_is_preserved_through_unwind() {
        let _silent = SilentPanicHook::install();
        // Captured-by-move state is allowed even though the closure would
        // not be UnwindSafe without `AssertUnwindSafe`.
        let sentinel = String::from("sentinel");
        let result = ffi_boundary(String::from("fallback"), move || {
            let _used = sentinel;
            panic!("simulated FFI panic");
        });
        assert_eq!(result, "fallback");
    }

    // Tests below mirror the exact return-type shapes used by JNI bridge
    // exports (`Java_*` functions) and lock the substitution contract that
    // those exports rely on when an adapter panics.

    #[test]
    fn jstring_shaped_export_returns_null_when_inner_panics() {
        let _silent = SilentPanicHook::install();
        let result: jni::sys::jstring = ffi_boundary(core::ptr::null_mut(), || panic!("inner panic"));
        assert!(result.is_null(), "panicked jstring export must surface as null to the JVM");
    }

    #[test]
    fn jboolean_shaped_export_returns_false_when_inner_panics() {
        let _silent = SilentPanicHook::install();
        let result: jni::sys::jboolean = ffi_boundary(jni::sys::JNI_FALSE, || panic!("inner panic"));
        assert_eq!(result, jni::sys::JNI_FALSE);
    }

    #[test]
    fn jlong_shaped_export_returns_zero_when_inner_panics() {
        let _silent = SilentPanicHook::install();
        // 0 is the "no handle" sentinel honored by every Java_* jniCreate caller.
        let result: jni::sys::jlong = ffi_boundary(0, || panic!("inner panic"));
        assert_eq!(result, 0);
    }

    #[test]
    fn jint_shaped_export_returns_caller_supplied_error_code_when_inner_panics() {
        let _silent = SilentPanicHook::install();
        // jniStart-style exports use 0=ok, non-zero=error; the wrapper must
        // never accidentally substitute 0 (which would tell the JVM the start
        // succeeded). The boundary returns whatever the caller picked.
        let result: jni::sys::jint = ffi_boundary(-1, || panic!("inner panic"));
        assert_eq!(result, -1);
    }

    #[test]
    fn simulated_jni_export_does_not_unwind_on_panic() {
        // Shape mirrors a Java_* bridge function: the adapter (`inner`) may
        // panic for any reason; `ffi_boundary` must convert that into a
        // sentinel return value so the caller never sees an unwind cross the
        // `extern "system"` boundary.
        fn simulated_inner() -> jni::sys::jstring {
            panic!("simulated adapter panic");
        }
        fn simulated_export() -> jni::sys::jstring {
            ffi_boundary(core::ptr::null_mut(), simulated_inner)
        }

        let _silent = SilentPanicHook::install();
        let returned = simulated_export();
        assert!(returned.is_null());
    }

    // NB: a live `extern "system" fn` invocation test exists alongside this
    // module in `tests/ffi_boundary_extern_system.rs` (integration-test
    // file). It exercises the production calling convention end-to-end
    // (function pointer with the same ABI as a `Java_*` JNI export, called
    // with a panicking inner). The integration test lives under `tests/`
    // because the workspace `check_unsafe_boundaries.py` / `check_ffi_
    // panic_boundary.py` scanners deliberately do not walk `tests/`, and
    // declaring extra `extern "system" fn`s in `src/` purely for the test
    // would otherwise require allowlist entries.
}
