use jni::objects::JString;
use jni::sys::{jboolean, jstring};

use ripdpi_android_proxy_adapter::{proxy_geoip_metadata_entry, proxy_validate_geo_database_entry};

export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniGeoIpMetadata,
    (geoip_db_path: JString, geosite_db_path: JString, ip: JString),
    jstring,
    proxy_geoip_metadata_entry,
    core::ptr::null_mut(),
);

export_jni!(
    Java_com_poyka_ripdpi_core_NativeGeoDatabaseValidator_jniValidateGeoDatabase,
    (path: JString, is_geosite: jboolean),
    jboolean,
    proxy_validate_geo_database_entry,
    jni::sys::JNI_FALSE,
);
