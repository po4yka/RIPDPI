/// Records one capability-skipped event for the named capability.
///
/// The counter key follows the pattern
/// `capability_skipped{capability=<name>}` where `<name>` is the
/// stable snake_case identifier returned by
/// `RuntimeCapability::as_str()` (e.g. `"ttl_write"`).
///
/// This helper is exported for monitor, proxy-runtime, or runtime-service callers to invoke when a strategy candidate is demoted or skipped because a required platform capability was detected as unavailable. Current production code does not call it yet; it is metric plumbing only.
pub fn record_capability_skipped(capability: &str) {
    metrics::counter!("capability_skipped", "capability" => capability.to_owned()).increment(1);
}
