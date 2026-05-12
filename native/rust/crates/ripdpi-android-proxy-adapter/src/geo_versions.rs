use std::path::PathBuf;

use android_support::throw_illegal_argument_env;
use jni::objects::JString;
use jni::sys::jstring;
use jni::Env;
use ripdpi_proxy_runtime::RuntimeGeoDatabaseVersions;
use serde::Serialize;

use ripdpi_android_bridge_support::JniProxyError;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct GeoDatabaseVersionsPayload {
    geoip_version: Option<String>,
    geosite_version: Option<String>,
}

pub(crate) fn geo_database_versions(env: &mut Env<'_>, geoip_db_path: JString, geosite_db_path: JString) -> jstring {
    let Ok(geoip_db_path) = geoip_db_path.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid geoip database path");
        return std::ptr::null_mut();
    };
    let Ok(geosite_db_path) = geosite_db_path.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid geosite database path");
        return std::ptr::null_mut();
    };

    let versions = match ripdpi_proxy_runtime::load_geo_database_versions(
        PathBuf::from(geoip_db_path),
        PathBuf::from(geosite_db_path),
    ) {
        Ok(versions) => versions,
        Err(err) => {
            JniProxyError::InvalidConfig(err.to_string()).throw(env);
            return std::ptr::null_mut();
        }
    };

    encode_versions(env, versions)
}

fn encode_versions(env: &mut Env<'_>, versions: RuntimeGeoDatabaseVersions) -> jstring {
    let payload = GeoDatabaseVersionsPayload { geoip_version: versions.geoip, geosite_version: versions.geosite };
    match serde_json::to_string(&payload) {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(err) => {
                JniProxyError::InvalidArgument(err.to_string()).throw(env);
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            JniProxyError::Serialization(err).throw(env);
            std::ptr::null_mut()
        }
    }
}
