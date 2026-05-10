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

mod cdn_ech_bridge;
mod diagnostics_bridge;
mod lua_bridge;
mod owned_tls_http_bridge;
mod platform_bridge;
mod proxy_bridge;
mod shared_priors_bridge;
mod vpn_protect_bridge;

pub use cdn_ech_bridge::*;
pub use diagnostics_bridge::*;
pub use lua_bridge::*;
pub use owned_tls_http_bridge::*;
pub use platform_bridge::*;
pub use proxy_bridge::*;
pub use shared_priors_bridge::*;
pub use vpn_protect_bridge::*;
