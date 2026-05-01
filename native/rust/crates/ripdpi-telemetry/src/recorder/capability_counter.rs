/// Records one capability-skipped event for the named capability.
///
/// The counter key follows the pattern
/// `capability_skipped{capability=<name>}` where `<name>` is the
/// stable snake_case identifier returned by
/// `RuntimeCapability::as_str()` (e.g. `"ttl_write"`).
///
/// Callers (ripdpi-monitor, ripdpi-runtime) should invoke this when a
/// strategy candidate is demoted or skipped because a required
/// platform capability was detected as unavailable.  Wiring from
/// emitter paths is deferred to slice 2.5; this function provides the
/// plumbing only.
pub fn record_capability_skipped(capability: &str) {
    metrics::counter!("capability_skipped", "capability" => capability.to_owned()).increment(1);
}
