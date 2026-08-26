# Change: Stop cascading relay and VPN startup failures

Task ID: `RLY-1786707070050078`

## Why

The simple-flavor VPN can have a validated TUN and successful relay traffic while a synthetic HTTP/DNS health check reports `tcp_connect` failure. Startup and steady-state failover currently interpret that target-specific result as proof that the complete relay tuple is broken, repeat probes while a failure latch remains set, and can cascade through cooldown, restarts, and final startup failure. Users need fail-closed startup without destroying a working data plane or entering a retry storm.

## What Changes

- Use one evidence contract for initial relay selection and steady-state failover, distinguishing positive data-plane evidence, target-specific inconclusive probe outcomes, and confirmed relay failures.
- Derive synthetic probes from the imported profile instead of using a separate runtime-only public endpoint.
- Bound probe concurrency, retry cadence, candidate attempts, cooldown scope, and cleanup ordering.
- Keep local runtime readiness separate from verified VPN egress and export a privacy-safe decision trace with exact relay failure stages.
- Restore the relevant TCP-only fallback, stage telemetry, and serialized cleanup invariants that regressed from `main`, without reverting unrelated UI work.

## Capabilities

### New Capabilities

- `relay-health-evidence`: Evidence-based relay readiness and failover decisions shared by startup and runtime supervision.

### Modified Capabilities

- None.

## Impact

- Affects simple-flavor relay selection in `:app`, VPN/proxy lifecycle orchestration in `:core:service`, service telemetry and diagnostics export contracts, and relay-stage events in the repository-owned Rust runtime.
- Adds internal Kotlin health-observation and decision types plus privacy-safe telemetry fields; any Rust/Kotlin wire additions require synchronized manifests, schema handling, migrations, and reviewed goldens.
- Requires focused Kotlin/Rust gates and physical Pixel 7 acceptance with the owner-controlled `dad-phone` bundle; historical evidence from another artifact or device is not sufficient.
