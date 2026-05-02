use jni::objects::JString;
use jni::sys::{jboolean, jint, jlong, jstring};

use crate::proxy::{
    pcap_is_recording_entry, pcap_start_entry, pcap_stop_entry, proxy_create_entry, proxy_destroy_entry,
    proxy_poll_telemetry_entry, proxy_start_entry, proxy_stop_entry, proxy_update_network_snapshot_entry,
};

export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate,
    (config_json: JString),
    jlong,
    proxy_create_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart,
    (handle: jlong),
    jint,
    proxy_start_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop,
    (handle: jlong),
    (),
    proxy_stop_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry,
    (handle: jlong),
    jstring,
    proxy_poll_telemetry_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    proxy_destroy_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot,
    (handle: jlong, snapshot_json: JString),
    (),
    proxy_update_network_snapshot_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStartPcapRecording,
    (handle: jlong, dir_path: JString, max_bytes: jlong),
    jboolean,
    pcap_start_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStopPcapRecording,
    (handle: jlong),
    jstring,
    pcap_stop_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniIsPcapRecording,
    (handle: jlong),
    jboolean,
    pcap_is_recording_entry
);
