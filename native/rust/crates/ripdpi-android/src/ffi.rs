//! JNI export facade.
//!
//! Keep JNI symbols in this root module, while feature-specific body code lives
//! in the sibling bridge modules below. That keeps the Android cdylib as a
//! loader/export boundary instead of a single feature dependency hub.
//!
//! Every export wraps the delegate call in `android_support::ffi_boundary` so a
//! Rust panic in the adapter layer cannot unwind across the `extern "system"`
//! boundary (which would be UB under the `android-jni` profile that uses
//! `panic = "unwind"`). The `$panic_default` token supplies the sentinel value
//! returned to the JVM when a panic is contained — for `jstring` / `jobjectArray`
//! that is `core::ptr::null_mut()`; for `jboolean` it is `jni::sys::JNI_FALSE`;
//! for `jlong` / `jint` it is a return code the Kotlin caller already treats as
//! a failure ("no handle" or a non-success status).

macro_rules! export_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident, $panic_default:expr_2021 $(,)?) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(
            env: jni::EnvUnowned<'_>,
            _thiz: jni::objects::JObject<'_>,
            $($arg: $arg_ty),*
        ) -> $ret {
            ::android_support::ffi_boundary::<$ret, _>($panic_default, move || $entry(env, $($arg),*))
        }
    };
}

mod bridges;

pub use bridges::*;
