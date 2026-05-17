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
    0,
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
