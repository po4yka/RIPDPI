# Local offline optimization

RIPDPI owns both implementations. It does not depend on metaheuRUSTics.
The searches process existing observations. They do not send network probes.

## DNS score calibration

`ripdpi-dns-resolver/src/health/calibration.rs` fits the three endpoint score
weights with DE/rand/1/bin. Input features are captured before the exchange
outcome updates the EWMA. The target is the measured transport utility:
zero for failure, and 0.75 to 1.0 for success based on latency up to 2000 ms.
This target measures transport quality; it is not proof of DNS answer correctness.

- Keep at most 128 samples per network scope, for at most ten minutes.
- Start fitting at 64 samples, then after each 32 new observations.
- Train on the oldest 75 percent; validate on the latest 25 percent.
- Use 12 population members and 20 generations: at most 252 training evaluations.
- Keep success weight in 0.45 to 0.70, latency in 0.10 to 0.30, and oracle
  weight at least 0.20. Weights sum to one.
- Accept only a validation improvement over both default and current weights.
  Restore defaults if current weights become worse or data loses contrast.
- Fit outside the registry mutex. A generation and scope incarnation check
  rejects a result if its history changed or its scope was evicted. A rejected
  interval fit reruns once on the next outcome if the scope still has enough samples.

The existing default weights are 0.55, 0.25, and 0.20. Quarantine remains the
first endpoint sort key. Bootstrap address scoring keeps the defaults.
The registry still holds at most 32 network scopes.

## Compact repeat diagnostics

`DiagnosticsTargetOptimizer` receives complete manual reports during normal
scan finalization. It holds up to three reports per network scope, path,
strategy suite, and settings, for at most 16 keys. The key ignores the report's
profile and the selected diagnostics profile setting, because evidence is matched
per host and an unobserved host stays mandatory. Each report is limited to 256 results.
Compaction uses the reports that are less than 30 minutes old; one is enough.
Restarting the process clears it.
Partial or oversized reports invalidate older evidence for the same key.

Before automatic scan admission applies the existing target budget,
`CompactProbeSearch` uses 512 seeded Simulated Annealing proposals. It retains
observed signal coverage, controls, and unknown or unhealthy targets. Targets
that share a concurrency cohort are kept or dropped as one unit. A concurrency
cohort can be dropped only when, in every report in the window, each member has
at least one `connection_concurrency` result and all of them are healthy, and the
other targets cover its signals. A selection fills the budget as far as whole
units allow. It
prefers lower measured latency among equivalent healthy targets. A missing
duration uses a neutral cost.

For predefined audit cohorts (`StrategyProbeTargetCohortSpec`), the search selects one complete existing cohort.
It cannot invent a cohort or change its matrix size. If one cohort cannot retain
the required evidence, normal cohort rotation remains in use. Other automatic
strategy probes keep their required target counts and existing caps. Full manual
scans, explicit protocol measurement profiles, and DNS-corrected re-probes are
not compacted.

Coverage signatures retain each strategy candidate ID and outcome. Missing
transport evidence, missing TCP baseline evidence, and unhealthy candidate
outcomes are mandatory. A candidate success cannot hide another candidate's failure.
No model or raw network identifier is exported or added to persistent storage.

## Validation limits

Unit tests check search behavior, chronology, bounded state, scope replacement,
quarantine, coverage, and planning integration. They do not establish a live
network speed or battery improvement. Compare those outcomes on a device before
claiming a performance gain.
