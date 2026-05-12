use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;

use ripdpi_geo::{GeoRuntime, GeoRuntimePaths, GeoRuntimeWarning};
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::services::GeoMatcher;
use tracing::warn;

#[derive(Debug)]
pub(super) struct RuntimeGeoMatcher {
    runtime: GeoRuntime,
}

impl RuntimeGeoMatcher {
    fn load(paths: GeoRuntimePaths) -> Option<Arc<Self>> {
        match GeoRuntime::load(paths) {
            Ok(load) => {
                for warning in load.warnings {
                    log_warning(warning);
                }
                Some(Arc::new(Self { runtime: load.runtime }))
            }
            Err(error) => {
                warn!(target: "GeoRuntime", %error, "geo runtime disabled");
                None
            }
        }
    }
}

impl GeoMatcher for RuntimeGeoMatcher {
    fn country_matches_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.runtime.country_contains_ip(country_code, ip)
    }

    fn geosite_matches_host(&self, category: &str, host: &str) -> bool {
        self.runtime.geosite_contains(category, host)
    }
}

pub(super) fn load_runtime_geo_matcher(config: &RuntimeConfig) -> Option<Arc<dyn GeoMatcher + Send + Sync>> {
    let geoip_db = config.process.geoip_db_path.as_ref()?;
    let geosite_db = config.process.geosite_db_path.as_ref()?;
    RuntimeGeoMatcher::load(GeoRuntimePaths {
        geoip_db: PathBuf::from(geoip_db),
        geosite_db: PathBuf::from(geosite_db),
    })
    .map(|matcher| matcher as Arc<dyn GeoMatcher + Send + Sync>)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeGeoDatabaseVersions {
    pub geoip: Option<String>,
    pub geosite: Option<String>,
}

pub fn load_geo_database_versions(
    geoip_db: PathBuf,
    geosite_db: PathBuf,
) -> Result<RuntimeGeoDatabaseVersions, ripdpi_geo::GeoRuntimeError> {
    let load = GeoRuntime::load(GeoRuntimePaths { geoip_db, geosite_db })?;
    let versions = load.runtime.versions();
    Ok(RuntimeGeoDatabaseVersions { geoip: versions.geoip, geosite: versions.geosite })
}

fn log_warning(warning: GeoRuntimeWarning) {
    match warning {
        GeoRuntimeWarning::MissingGeoip(path) => {
            warn!(target: "GeoRuntime", path = %path.display(), "geoip database missing; geoip rules disabled");
        }
        GeoRuntimeWarning::MissingGeosite(path) => {
            warn!(target: "GeoRuntime", path = %path.display(), "geosite database missing; geosite rules disabled");
        }
    }
}
