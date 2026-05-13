use jni::objects::JString;
use jni::sys::jstring;

use ripdpi_android_proxy_adapter::{proxy_geo_database_versions_entry, proxy_geoip_metadata_entry};

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
