use std::path::PathBuf;
use std::str::FromStr;

use android_support::throw_illegal_argument_env;
use jni::Env;
use jni::objects::JString;
use jni::sys::jstring;
use ripdpi_proxy_runtime::{RuntimeGeoDatabaseVersions, RuntimeGeoIpMetadata};
use serde::Serialize;

use ripdpi_android_bridge_support::JniProxyError;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct GeoDatabaseVersionsPayload {
    geoip_version: Option<String>,
    geosite_version: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct GeoIpMetadataPayload {
    country_code: Option<String>,
    asn: Option<u32>,
    organization: Option<String>,
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

pub(crate) fn geoip_metadata(
    env: &mut Env<'_>,
    geoip_db_path: JString,
    geosite_db_path: JString,
    ip: JString,
) -> jstring {
    let Ok(geoip_db_path) = geoip_db_path.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid geoip database path");
        return std::ptr::null_mut();
    };
    let Ok(geosite_db_path) = geosite_db_path.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid geosite database path");
        return std::ptr::null_mut();
    };
    let Ok(ip) = ip.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid geoip metadata address");
        return std::ptr::null_mut();
    };
    let Ok(ip) = std::net::IpAddr::from_str(&ip) else {
        throw_illegal_argument_env(env, "Invalid geoip metadata address");
        return std::ptr::null_mut();
    };

    let metadata = match ripdpi_proxy_runtime::load_geoip_metadata(
        PathBuf::from(geoip_db_path),
        PathBuf::from(geosite_db_path),
        ip,
    ) {
        Ok(metadata) => metadata,
        Err(err) => {
            JniProxyError::InvalidConfig(err.to_string()).throw(env);
            return std::ptr::null_mut();
        }
    };

    encode_metadata(env, metadata)
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

fn encode_metadata(env: &mut Env<'_>, metadata: Option<RuntimeGeoIpMetadata>) -> jstring {
    let payload = metadata.map(|metadata| GeoIpMetadataPayload {
        country_code: metadata.country_code,
        asn: metadata.asn,
        organization: metadata.organization,
    });
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
