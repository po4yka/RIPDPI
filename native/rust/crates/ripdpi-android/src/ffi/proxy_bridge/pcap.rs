use jni::objects::JString;
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE};

use ripdpi_android_proxy_adapter::{pcap_is_recording_entry, pcap_start_entry, pcap_stop_entry};

export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStartPcapRecording,
    (handle: jlong, dir_path: JString, max_bytes: jlong),
    jboolean,
    pcap_start_entry,
    JNI_FALSE,
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStopPcapRecording,
    (handle: jlong),
    jstring,
    pcap_stop_entry,
    core::ptr::null_mut(),
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniIsPcapRecording,
    (handle: jlong),
    jboolean,
    pcap_is_recording_entry,
    JNI_FALSE,
);
