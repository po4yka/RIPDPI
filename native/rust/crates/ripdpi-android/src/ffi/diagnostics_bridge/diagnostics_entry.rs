//! JNI export facade for the network-diagnostics session lifecycle.
//!
//! Each `export_jni!` entry is a `Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_*`
//! symbol that wraps a `diagnostics_*_entry` delegate from
//! `ripdpi-android-diagnostics-adapter` in `android_support::ffi_boundary` for
//! panic containment. This module owns only the export symbols and panic
//! sentinels; the scan logic lives in the adapter crate.
//!
//! ## Handle lifecycle
//! `jniCreate` registers a session and returns an opaque `jlong` registry key
//! (`0` on failure). The handle then accepts repeated scan cycles —
//! `jniStartScan` followed by `jniPollProgress`/`jniTakeReport` and an optional
//! `jniCancelScan` — until `jniDestroy` retires it. `jniPollPassiveEvents` is
//! independent of scan state.
//!
//! ## Idempotency, fds, errors, blocking
//! `jniStartScan` throws `IllegalStateException` if a scan is already running
//! (not idempotent); `jniCancelScan` is idempotent (a no-op when idle);
//! `jniTakeReport` consumes the report (take semantics). Diagnostics adopts no
//! externally supplied fds and registers no callbacks — the Kotlin side polls.
//! No entry blocks: `jniStartScan` spawns a native worker and returns. Failures
//! surface as Java exceptions (`IllegalArgumentException`,
//! `IllegalStateException`, `RuntimeException`); a contained panic yields the
//! panic-default sentinel (`0` handle or null `jstring`).
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §7 (error
//! mapping), §8 (callback rules) and `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`.

use jni::objects::JString;
use jni::sys::{jlong, jstring};
use ripdpi_android_diagnostics_adapter::{
    diagnostics_cancel_scan_entry, diagnostics_create_entry, diagnostics_destroy_entry,
    diagnostics_poll_passive_events_entry, diagnostics_poll_progress_entry, diagnostics_start_scan_entry,
    diagnostics_take_report_entry,
};

export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate,
    (),
    jlong,
    diagnostics_create_entry,
    0
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan,
    (handle: jlong, request_json: JString, session_id: JString),
    (),
    diagnostics_start_scan_entry,
    (),
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan,
    (handle: jlong),
    (),
    diagnostics_cancel_scan_entry,
    (),
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress,
    (handle: jlong),
    jstring,
    diagnostics_poll_progress_entry,
    core::ptr::null_mut(),
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport,
    (handle: jlong),
    jstring,
    diagnostics_take_report_entry,
    core::ptr::null_mut(),
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents,
    (handle: jlong),
    jstring,
    diagnostics_poll_passive_events_entry,
    core::ptr::null_mut(),
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    diagnostics_destroy_entry,
    (),
);
