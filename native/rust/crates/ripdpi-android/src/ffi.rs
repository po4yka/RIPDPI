//! JNI export facade.
//!
//! Keep JNI symbols in this root module, while feature-specific body code lives
//! in the sibling bridge modules below. That keeps the Android cdylib as a
//! loader/export boundary instead of a single feature dependency hub.

macro_rules! export_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(
            env: jni::EnvUnowned<'_>,
            _thiz: jni::objects::JObject<'_>,
            $($arg: $arg_ty),*
        ) -> $ret {
            $entry(env, $($arg),*)
        }
    };
}

mod bridges;

pub use bridges::*;
