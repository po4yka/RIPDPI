//! Live `extern "system" fn` invocation test for `ffi_boundary`.
//!
//! Each test fn here defines an `extern "system" fn` whose body wraps a
//! panicking inner with `ffi_boundary(default, || panic!(...))`. The
//! function pointer is obtained as `extern "system" fn(...) -> T` -- the
//! same ABI the JVM holds for every `Java_*` JNI export -- and called.
//! Returning normally (instead of unwinding) proves the panic was caught
//! before it could reach the foreign calling-convention frame.
//!
//! The four shapes mirror the JNI return types covered by `ffi_boundary`
//! production use:
//!   - `jint` (`i32`)       -- jniStart-style status, default `-1`
//!   - `jstring` (`*mut _`) -- jniPoll*-style nullable, default `null_mut`
//!   - `jboolean` (`u8`)    -- jniIs*-style flag, default `JNI_FALSE`
//!   - `()`                 -- jniStop/jniDestroy, default `()`
//!
//! Lives in `tests/` (integration-test directory) rather than the unit
//! module inside `src/ffi_boundary.rs` so the workspace `extern fn`
//! soundness scanners (`check_unsafe_boundaries.py`,
//! `check_ffi_panic_boundary.py`) do not need allowlist entries for the
//! test-only definitions.

use android_support::ffi_boundary;
use std::sync::Mutex;

static PANIC_HOOK_GUARD: Mutex<()> = Mutex::new(());

type PanicHook = Box<dyn Fn(&std::panic::PanicHookInfo<'_>) + Send + Sync + 'static>;

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

extern "system" fn extern_system_jint_panic_export() -> jni::sys::jint {
    ffi_boundary(-1_i32, || panic!("forced panic at FFI boundary"))
}

extern "system" fn extern_system_jstring_panic_export() -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), || panic!("forced panic at FFI boundary"))
}

extern "system" fn extern_system_jboolean_panic_export() -> jni::sys::jboolean {
    ffi_boundary(jni::sys::JNI_FALSE, || panic!("forced panic at FFI boundary"))
}

extern "system" fn extern_system_unit_panic_export() {
    ffi_boundary((), || panic!("forced panic at FFI boundary"));
}

#[test]
fn real_extern_system_fn_called_via_pointer_does_not_unwind_on_panic() {
    let _silent = SilentPanicHook::install();

    let jint_export: extern "system" fn() -> jni::sys::jint = extern_system_jint_panic_export;
    assert_eq!(jint_export(), -1, "jint export must surface caller-supplied error sentinel on panic");

    let jstring_export: extern "system" fn() -> jni::sys::jstring = extern_system_jstring_panic_export;
    assert!(jstring_export().is_null(), "jstring export must surface null on panic");

    let jboolean_export: extern "system" fn() -> jni::sys::jboolean = extern_system_jboolean_panic_export;
    assert_eq!(jboolean_export(), jni::sys::JNI_FALSE, "jboolean export must surface JNI_FALSE on panic");

    let unit_export: extern "system" fn() = extern_system_unit_panic_export;
    // Returning normally is the property under test -- a real unwind here
    // would propagate as a test failure instead of a clean return.
    unit_export();
}
