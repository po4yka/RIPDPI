use jni::objects::JString;
use jni::sys::{jboolean, jint, jlong, jstring};

use ripdpi_android_proxy_adapter::{
    pcap_is_recording_entry, pcap_start_entry, pcap_stop_entry, proxy_create_entry, proxy_destroy_entry,
    proxy_geo_database_versions_entry, proxy_geoip_metadata_entry, proxy_poll_telemetry_entry, proxy_start_entry,
    proxy_stop_entry, proxy_update_network_snapshot_entry,
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
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniGeoDatabaseVersions,
    (geoip_db_path: JString, geosite_db_path: JString),
    jstring,
    proxy_geo_database_versions_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniGeoIpMetadata,
    (geoip_db_path: JString, geosite_db_path: JString, ip: JString),
    jstring,
    proxy_geoip_metadata_entry
);
