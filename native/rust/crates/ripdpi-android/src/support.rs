use std::sync::MutexGuard;

use android_support::describe_exception;
use jni::objects::JString;
use jni::sys::jstring;
use jni::JNIEnv;

pub(crate) fn lock_jni_tests() -> MutexGuard<'static, ()> {
    crate::shared_jni_test_mutex().lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub(crate) fn with_env<R>(f: impl FnOnce(&mut JNIEnv<'_>) -> R) -> R {
    let mut env = crate::shared_test_jvm().attach_current_thread().expect("attach current thread to test JVM");
    f(&mut env)
}

pub(crate) fn take_exception(env: &mut JNIEnv<'_>) -> String {
    describe_exception(env).expect("expected Java exception")
}

pub(crate) fn decode_jstring(env: &mut JNIEnv<'_>, value: jstring) -> Option<String> {
    (!value.is_null()).then(|| {
        let value = unsafe { JString::from_raw(value) };
        let text = env.get_string(&value).expect("decode jstring");
        text.into()
    })
}

pub(crate) fn assert_no_exception(env: &mut JNIEnv<'_>) {
    if let Some(exception) = describe_exception(env) {
        panic!("unexpected Java exception: {exception}");
    }
}
