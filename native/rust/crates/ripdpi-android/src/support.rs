use std::sync::MutexGuard;

use android_support::describe_exception;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{Env, EnvUnowned};

pub(crate) fn lock_jni_tests() -> MutexGuard<'static, ()> {
    crate::tests::shared_jni_test_mutex().lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub(crate) fn with_env<R>(f: impl for<'a> FnOnce(&mut Env<'a>) -> R) -> R {
    crate::tests::shared_test_jvm()
        .attach_current_thread(|env| Ok::<_, jni::errors::Error>(f(env)))
        .expect("attach current thread to test JVM")
}

/// Create an `EnvUnowned` from an `Env` reference for calling FFI entry points
/// and `describe_exception`.
///
/// # Safety
/// The returned `EnvUnowned` borrows the same JNI env pointer and must not
/// outlive the `Env` it was derived from.
pub(crate) fn env_to_unowned<'local>(env: &mut Env<'local>) -> EnvUnowned<'local> {
    // SAFETY: `env.get_raw()` returns the current JNI env pointer for this
    // thread; the returned `EnvUnowned` stays within the caller's borrow.
    unsafe { EnvUnowned::from_raw(env.get_raw()) }
}

pub(crate) fn take_exception(env: &mut Env<'_>) -> String {
    let mut unowned = env_to_unowned(env);
    describe_exception(&mut unowned).expect("expected Java exception")
}

pub(crate) fn decode_jstring(env: &mut Env<'_>, value: jstring) -> Option<String> {
    (!value.is_null()).then(|| {
        // SAFETY: `value` is a live local JNI string reference in the current
        // frame and is consumed exactly once by `from_raw`.
        let value = unsafe { JString::from_raw(env, value) };
        value.try_to_string(env).expect("decode jstring")
    })
}

pub(crate) fn assert_no_exception(env: &mut Env<'_>) {
    let mut unowned = env_to_unowned(env);
    if let Some(exception) = describe_exception(&mut unowned) {
        panic!("unexpected Java exception: {exception}");
    }
}
