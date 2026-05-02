use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use jni::EnvUnowned;

use crate::ffi::cdn_ech;

// JNI bridge for the process-wide CdnEchUpdater. The Kotlin
// CdnEchRefreshWorker calls these on its refresh/persistence lifecycle.

/// Refresh the singleton's cache from primary (DoH HTTPS-RR) or fallback
/// (bundled) source.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniRefreshCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    cdn_ech::refresh_entry(env)
}

/// Snapshot the current cache for persistence to platform storage.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSnapshotCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    cdn_ech::snapshot_entry(env)
}

/// Seed the singleton's cache from a previously-persisted snapshot.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSeedCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    config_base64: JString<'_>,
    fetched_at_unix_ms: jlong,
) -> jstring {
    cdn_ech::seed_entry(env, config_base64, fetched_at_unix_ms)
}
