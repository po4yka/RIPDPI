mod polling;
mod registry;
mod scan;

use android_support::{init_android_logging, throw_runtime_exception};
use jni::objects::JString;
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use crate::errors::extract_panic_message;
use polling::{poll_passive_events, poll_progress, take_report};
use registry::{create_diagnostics_session, destroy_diagnostics_session};
use scan::{cancel_diagnostics_scan, start_diagnostics_scan};

pub(crate) fn diagnostics_create_entry(mut env: JNIEnv) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_diagnostics_session())).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics session creation panicked: {msg}"));
            0
        },
    )
}

pub(crate) fn diagnostics_start_scan_entry(mut env: JNIEnv, handle: jlong, request_json: JString, session_id: JString) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_diagnostics_scan(&mut env, handle, request_json, session_id);
    }))
    .map_err(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env, format!("Diagnostics scan start panicked: {msg}"));
    });
}

pub(crate) fn diagnostics_cancel_scan_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cancel_diagnostics_scan(&mut env, handle)))
        .map_err(|panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics cancel panicked: {msg}"));
        });
}

pub(crate) fn diagnostics_poll_progress_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| poll_progress(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics progress polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn diagnostics_take_report_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| take_report(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics report polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn diagnostics_poll_passive_events_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| poll_passive_events(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics passive polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn diagnostics_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_diagnostics_session(&mut env, handle)))
        .map_err(|panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Diagnostics session destroy panicked: {msg}"));
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::sync::MutexGuard;
    use std::thread::sleep;
    use std::time::{Duration, Instant};

    use android_support::describe_exception;
    use jni::objects::{JObject, JString};
    use jni::JNIEnv;
    use ripdpi_monitor::{NativeSessionEvent, ScanProgress, ScanReport};

    struct DiagnosticsHandle {
        raw: jlong,
    }

    impl DiagnosticsHandle {
        fn new() -> Self {
            let raw = with_env(|env| {
                let handle = jni_create(env);
                assert_no_exception(env);
                handle
            });
            assert_ne!(raw, 0, "jniCreate should return a non-zero diagnostics handle");
            Self { raw }
        }

        fn raw(&self) -> jlong {
            self.raw
        }

        fn disarm(&mut self) -> jlong {
            let raw = self.raw;
            self.raw = 0;
            raw
        }
    }

    impl Drop for DiagnosticsHandle {
        fn drop(&mut self) {
            if self.raw == 0 {
                return;
            }
            with_env(|env| {
                jni_destroy(env, self.raw);
                let _ = describe_exception(env);
            });
        }
    }

    #[test]
    fn jni_create_and_destroy_round_trip_without_exception() {
        let _serial = lock_jni_tests();
        let mut handle = DiagnosticsHandle::new();

        let stale_handle = handle.raw();
        with_env(|env| {
            jni_destroy(env, stale_handle);
            assert_no_exception(env);
        });
        handle.disarm();
    }

    #[test]
    fn fresh_handle_returns_expected_null_or_empty_values() {
        let _serial = lock_jni_tests();
        let handle = DiagnosticsHandle::new();

        with_env(|env| {
            let progress = jni_poll_progress(env, handle.raw());
            assert!(decode_jstring(env, progress).is_none());
            assert_no_exception(env);

            let report = jni_take_report(env, handle.raw());
            assert!(decode_jstring(env, report).is_none());
            assert_no_exception(env);

            let events = jni_poll_passive_events(env, handle.raw());
            let events_json = decode_jstring(env, events).expect("fresh handle events json");
            assert_no_exception(env);

            let events: Vec<NativeSessionEvent> = serde_json::from_str(&events_json).expect("decode empty events");
            assert!(events.is_empty());
        });
    }

    #[test]
    fn malformed_request_json_throws_illegal_argument() {
        let _serial = lock_jni_tests();
        let handle = DiagnosticsHandle::new();

        with_env(|env| {
            jni_start_scan(env, handle.raw(), "{", "session-malformed");
            let exception = take_exception(env);
            assert!(exception.starts_with("java.lang.IllegalArgumentException: Invalid diagnostics request:"));
        });
    }

    #[test]
    fn valid_request_produces_progress_and_report_json() {
        let _serial = lock_jni_tests();
        let handle = DiagnosticsHandle::new();
        let session_id = "jni-success-session";

        with_env(|env| {
            jni_start_scan(env, handle.raw(), &minimal_request_json(), session_id);
            assert_no_exception(env);
        });

        let progress_json = wait_for_finished_progress(handle.raw());
        let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode progress");
        assert_eq!(progress.session_id, session_id);
        assert!(progress.is_finished);

        let report_json = wait_for_json(handle.raw(), jni_take_report);
        let report: ScanReport = serde_json::from_str(&report_json).expect("decode report");
        assert_eq!(report.session_id, session_id);
        assert_eq!(report.profile_id, "jni-test-profile");
        assert_eq!(report.summary, "0 completed · 0 healthy");
    }

    #[test]
    fn duplicate_start_throws_illegal_state() {
        let _serial = lock_jni_tests();
        let handle = DiagnosticsHandle::new();

        with_env(|env| {
            jni_start_scan(env, handle.raw(), &minimal_request_json(), "session-duplicate");
            assert_no_exception(env);

            jni_start_scan(env, handle.raw(), &minimal_request_json(), "session-duplicate-2");
            let exception = take_exception(env);
            assert_eq!(exception, "java.lang.IllegalStateException: diagnostics scan already running",);
        });
    }

    #[test]
    fn invalid_handles_throw_illegal_argument_and_string_ops_return_null() {
        let _serial = lock_jni_tests();

        for handle in [0, -1] {
            with_env(|env| {
                jni_cancel_scan(env, handle);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid diagnostics handle",);
            });

            with_env(|env| {
                let progress = jni_poll_progress(env, handle);
                assert!(decode_jstring(env, progress).is_none());
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid diagnostics handle",);
            });

            with_env(|env| {
                let report = jni_take_report(env, handle);
                assert!(decode_jstring(env, report).is_none());
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid diagnostics handle",);
            });

            with_env(|env| {
                let events = jni_poll_passive_events(env, handle);
                assert!(decode_jstring(env, events).is_none());
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid diagnostics handle",);
            });

            with_env(|env| {
                jni_destroy(env, handle);
                assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Invalid diagnostics handle",);
            });
        }
    }

    #[test]
    fn stale_handle_is_rejected_as_unknown() {
        let _serial = lock_jni_tests();
        let mut handle = DiagnosticsHandle::new();

        let stale_handle = handle.raw();
        with_env(|env| {
            jni_destroy(env, stale_handle);
            assert_no_exception(env);
        });
        handle.disarm();

        with_env(|env| {
            jni_start_scan(env, stale_handle, &minimal_request_json(), "session-stale");
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown diagnostics handle",);
        });

        with_env(|env| {
            let progress = jni_poll_progress(env, stale_handle);
            assert!(decode_jstring(env, progress).is_none());
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown diagnostics handle",);
        });

        with_env(|env| {
            jni_cancel_scan(env, stale_handle);
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: Unknown diagnostics handle",);
        });
    }

    fn lock_jni_tests() -> MutexGuard<'static, ()> {
        crate::shared_jni_test_mutex().lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn with_env<R>(f: impl FnOnce(&mut JNIEnv<'_>) -> R) -> R {
        let mut env = crate::shared_test_jvm().attach_current_thread().expect("attach current thread to test JVM");
        f(&mut env)
    }

    fn jni_create(env: &mut JNIEnv<'_>) -> jlong {
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate(
            unsafe { env.unsafe_clone() },
            JObject::null(),
        )
    }

    fn jni_start_scan(env: &mut JNIEnv<'_>, handle: jlong, request_json: &str, session_id: &str) {
        let request_json = env.new_string(request_json).expect("create request json string");
        let session_id = env.new_string(session_id).expect("create session id string");
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
            request_json,
            session_id,
        );
    }

    fn jni_cancel_scan(env: &mut JNIEnv<'_>, handle: jlong) {
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        );
    }

    fn jni_poll_progress(env: &mut JNIEnv<'_>, handle: jlong) -> jstring {
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_take_report(env: &mut JNIEnv<'_>, handle: jlong) -> jstring {
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_poll_passive_events(env: &mut JNIEnv<'_>, handle: jlong) -> jstring {
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        )
    }

    fn jni_destroy(env: &mut JNIEnv<'_>, handle: jlong) {
        crate::Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy(
            unsafe { env.unsafe_clone() },
            JObject::null(),
            handle,
        );
    }

    fn assert_no_exception(env: &mut JNIEnv<'_>) {
        assert!(describe_exception(env).is_none(), "unexpected pending Java exception");
    }

    fn take_exception(env: &mut JNIEnv<'_>) -> String {
        describe_exception(env).expect("expected Java exception")
    }

    fn decode_jstring(env: &mut JNIEnv<'_>, raw: jstring) -> Option<String> {
        if raw.is_null() {
            return None;
        }
        let string = unsafe { JString::from_raw(raw) };
        Some(env.get_string(&string).expect("read jstring").into())
    }

    fn wait_for_json(handle: jlong, op: fn(&mut JNIEnv<'_>, jlong) -> jstring) -> String {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            let maybe_json = with_env(|env| {
                let raw = op(env, handle);
                let value = decode_jstring(env, raw);
                assert_no_exception(env);
                value
            });
            if let Some(json) = maybe_json {
                return json;
            }
            assert!(Instant::now() < deadline, "timed out waiting for diagnostics JSON");
            sleep(Duration::from_millis(10));
        }
    }

    fn wait_for_finished_progress(handle: jlong) -> String {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            let progress_json = wait_for_json(handle, jni_poll_progress);
            let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode progress");
            if progress.is_finished {
                return progress_json;
            }
            assert!(Instant::now() < deadline, "timed out waiting for finished diagnostics progress");
            sleep(Duration::from_millis(10));
        }
    }

    fn minimal_request_json() -> String {
        serde_json::json!({
            "profileId": "jni-test-profile",
            "displayName": "JNI diagnostics",
            "pathMode": "RAW_PATH",
            "proxyHost": null,
            "proxyPort": null,
            "domainTargets": [],
            "dnsTargets": [],
            "tcpTargets": [],
            "whitelistSni": [],
        })
        .to_string()
    }
}
