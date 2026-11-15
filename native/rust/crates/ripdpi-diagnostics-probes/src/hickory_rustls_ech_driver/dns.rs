use std::time::Duration;

use hickory_resolver::{
    TokioResolver,
    config::{ResolveHosts, ResolverConfig, ResolverOpts},
    net::runtime::TokioRuntimeProvider,
    proto::rr::rdata::svcb::{SVCB, SvcParamKey, SvcParamValue},
    proto::rr::{Name, RData, RecordType},
};

/// # Cancel safety:
/// cancel-safe: the deadline future owns the complete resolver operation;
/// dropping it drops that operation and prevents its next retry.
pub(super) async fn lookup(
    host: &str,
    lookup_timeout: Duration,
    config: ResolverConfig,
) -> Result<Option<Vec<u8>>, String> {
    let deadline = tokio::time::Instant::now()
        .checked_add(lookup_timeout)
        .ok_or_else(|| "hickory-lookup-timeout-out-of-range".to_string())?;
    if lookup_timeout.is_zero() {
        return Err("hickory-lookup-timeout".into());
    }
    tokio::time::timeout_at(deadline, resolve(host, lookup_timeout, config))
        .await
        .map_err(|_| "hickory-lookup-timeout".to_string())?
}

/// # Cancel safety:
/// cancel-safe: the lookup owns its resolver and request state; dropping it
/// releases that state and prevents another attempt by this operation.
async fn resolve(host: &str, lookup_timeout: Duration, config: ResolverConfig) -> Result<Option<Vec<u8>>, String> {
    let mut opts = ResolverOpts::default();
    opts.timeout = lookup_timeout;
    // Hosts files have no HTTPS records; avoid their synchronous read during resolver setup.
    opts.use_hosts_file = ResolveHosts::Never;
    let resolver = TokioResolver::builder_with_config(config, TokioRuntimeProvider::default())
        .with_options(opts)
        .build()
        .map_err(|error| format!("hickory-build-error: {error}"))?;
    let target = Name::from_utf8(host).map_err(|error| format!("hickory-lookup-error: {error}"))?;
    let lookup = match resolver.lookup(target.clone(), RecordType::HTTPS).await {
        Ok(lookup) => lookup,
        Err(error) if error.is_no_records_found() && !error.is_nx_domain() => return Ok(None),
        Err(error) => return Err(format!("hickory-lookup-error: {error}")),
    };

    if lookup.answers().iter().any(|record| matches!(&record.data, RData::HTTPS(https) if https.0.svc_priority == 0)) {
        return Err("ech-dns-binding-unsupported".into());
    }
    let mut compatible_record_seen = false;
    let mut unsupported_record_seen = false;
    for record in lookup.answers() {
        let RData::HTTPS(https) = &record.data else {
            continue;
        };
        let service = &https.0;
        if !supported_service(service, &target) {
            unsupported_record_seen = true;
            continue;
        }
        compatible_record_seen = true;
        for (key, value) in &service.svc_params {
            if *key == SvcParamKey::EchConfigList
                && let SvcParamValue::EchConfigList(ech) = value
            {
                return Ok(Some(ech.0.clone()));
            }
        }
    }
    if unsupported_record_seen && !compatible_record_seen {
        Err("ech-dns-binding-unsupported".into())
    } else {
        Ok(None)
    }
}

fn supported_service(service: &SVCB, target: &Name) -> bool {
    if !service.target_name.is_root() && !service.target_name.eq_ignore_root(target) {
        return false;
    }
    if service.svc_params.iter().any(|(key, _)| *key == SvcParamKey::NoDefaultAlpn) {
        return false;
    }
    let port = service.svc_params.iter().find(|(key, _)| *key == SvcParamKey::Port);
    if port.is_some_and(|(_, value)| !matches!(value, SvcParamValue::Port(443))) {
        return false;
    }
    let Some((_, mandatory)) = service.svc_params.iter().find(|(key, _)| *key == SvcParamKey::Mandatory) else {
        return true;
    };
    let SvcParamValue::Mandatory(keys) = mandatory else {
        return false;
    };
    if keys.0.is_empty() || !keys.0.windows(2).all(|pair| u16::from(pair[0]) < u16::from(pair[1])) {
        return false;
    }
    // This probe can apply only ECH and the default HTTPS port.
    keys.0.iter().all(|key| match key {
        SvcParamKey::Port => matches!(port, Some((_, SvcParamValue::Port(443)))),
        SvcParamKey::EchConfigList => service
            .svc_params
            .iter()
            .any(|(key, value)| *key == SvcParamKey::EchConfigList && matches!(value, SvcParamValue::EchConfigList(_))),
        _ => false,
    })
}
