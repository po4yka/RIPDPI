mod events;
mod exceptions;
mod ffi_boundary;
mod handles;
mod http;
mod logging;
mod shared_jvm;
mod sync;
mod tracing_layer;

#[cfg(all(test, not(feature = "loom")))]
mod tests;

use jni::sys::jint;

pub use events::{
    EventRingBuffers, NativeEventRecord, RelayEventDrain, RingConfig, clear_diagnostics_events,
    clear_diagnostics_events_for_session, clear_proxy_events, clear_relay_events, clear_relay_events_for_runtime,
    clear_tunnel_events, clear_warp_events, drain_diagnostics_events, drain_diagnostics_events_for_session,
    drain_proxy_events, drain_relay_events, drain_relay_events_for_runtime, drain_tunnel_events, drain_warp_events,
};
pub use exceptions::{
    describe_exception, sanitize_error_message, throw_illegal_argument, throw_illegal_argument_env,
    throw_illegal_state, throw_illegal_state_env, throw_io_exception, throw_io_exception_env, throw_runtime_exception,
    throw_runtime_exception_env,
};
pub use ffi_boundary::ffi_boundary;
pub use handles::HandleRegistry;
pub use http::authority_header_value;
pub use logging::{
    android_log_level_from_debug_verbosity, android_log_level_from_str, clear_android_log_scope_level,
    default_android_log_level, ignore_sigpipe, init_android_logging, install_panic_hook, log_with_level,
    set_android_log_scope_level,
};
pub use shared_jvm::SharedJvm;
pub use tracing_layer::EventRingLayer;

pub const JNI_VERSION: jint = jni::sys::JNI_VERSION_1_6;
