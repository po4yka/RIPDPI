mod lifecycle;
mod registry;
mod telemetry;

#[cfg(test)]
mod tests;
#[cfg(feature = "loom")]
mod loom_tests;

use android_support::{init_android_logging, throw_runtime_exception};
use jni::objects::JString;
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::errors::extract_panic_message;

use lifecycle::{create_session, destroy_session, start_session, stop_session, update_network_snapshot};
use telemetry::poll_proxy_telemetry;

pub(crate) fn proxy_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session creation panicked: {msg}"));
            0
        },
    )
}

pub(crate) fn proxy_start_entry(mut env: JNIEnv, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session start panicked: {msg}"));
            libc::EINVAL
        },
    )
}

pub(crate) fn proxy_stop_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle))).map_err(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session stop panicked: {msg}"));
        },
    );
}

pub(crate) fn proxy_poll_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| poll_proxy_telemetry(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy telemetry polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn proxy_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle))).map_err(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session destroy panicked: {msg}"));
        },
    );
}

pub(crate) fn proxy_update_network_snapshot_entry(mut env: JNIEnv, handle: jlong, snapshot_json: JString) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        update_network_snapshot(&mut env, handle, snapshot_json);
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Proxy network snapshot update panicked: {msg}"));
    });
}
