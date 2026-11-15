use std::time::Duration;

use hickory_resolver::{
    TokioResolver,
    config::{ResolverConfig, ResolverOpts},
    net::runtime::TokioRuntimeProvider,
    proto::rr::rdata::svcb::{SvcParamKey, SvcParamValue},
    proto::rr::{RData, RecordType},
};

/// # Cancel safety:
/// cancel-safe: the lookup owns its resolver and request state; dropping it
/// releases that state and prevents another attempt by this operation.
pub(super) async fn lookup(
    host: &str,
    lookup_timeout: Duration,
    config: ResolverConfig,
) -> Result<Option<Vec<u8>>, String> {
    let mut opts = ResolverOpts::default();
    opts.timeout = lookup_timeout;
    let resolver = TokioResolver::builder_with_config(config, TokioRuntimeProvider::default())
        .with_options(opts)
        .build()
        .map_err(|error| format!("hickory-build-error: {error}"))?;
    let lookup = match resolver.lookup(host, RecordType::HTTPS).await {
        Ok(lookup) => lookup,
        Err(error) if error.is_no_records_found() && !error.is_nx_domain() => return Ok(None),
        Err(error) => return Err(format!("hickory-lookup-error: {error}")),
    };

    for record in lookup.answers() {
        let rdata: &RData = &record.data;
        let svc_params = match rdata {
            RData::HTTPS(https) => &https.0.svc_params,
            RData::SVCB(svcb) => &svcb.svc_params,
            _ => continue,
        };
        for (key, value) in svc_params {
            if *key == SvcParamKey::EchConfigList
                && let SvcParamValue::EchConfigList(ech) = value
            {
                return Ok(Some(ech.0.clone()));
            }
        }
    }
    Ok(None)
}
