# Change: Fix split-host strategy execution evidence

Task ID: `DGN-1786885244559735`

## Why

The current diagnostics report can state that `split(host+1)` did not work even
though it cannot prove that the configured marker was resolved, that the split
actions were executed, or that the attempt used the active service path. It
also aggregates unrelated RAW_PATH reports and matrix candidates under the
current strategy snapshot. This produces false negative and false positive
strategy verdicts and leaves support archives unable to distinguish a genuine
network-path rejection from launch, activation, planning, fallback, or runtime
failures.

## What Changes

- Evaluate the current strategy only from a complete `baseline_current`
  attempt that carries execution evidence for the exact configured strategy.
- Separate ephemeral RAW_PATH candidate evidence from active-service IN_PATH
  evidence; generic connectivity and unrelated candidates cannot validate or
  invalidate the active strategy.
- Report launch, activation, planning, fallback, execution, cancellation, and
  response-stage outcomes as typed evidence instead of collapsing them into
  `failed`.
- Export privacy-safe configured-versus-effective plan and execution receipts,
  with bounded counts and no destination, address, payload, credential, or
  interface identifiers.
- Make candidate isolation explicit so retained relay, WARP, WebSocket,
  rotation, adaptive, routing, or activation state cannot silently change the
  strategy being evaluated.
- BREAKING: diagnostics strategy evidence and verdict contracts gain typed
  execution provenance; consumers must handle unverified and incomplete
  outcomes rather than treating every failed probe as an evaluated strategy.

## Capabilities

### New Capabilities

- `diagnostics-strategy-execution-evidence`: Correlates a candidate generation
  with its configured and effective desync plan, action execution, write
  outcome, runtime terminal state, observation path, and response stage.
- `diagnostics-current-strategy-verdict`: Produces a verdict only from complete,
  exact-strategy evidence and preserves unverified or incomplete states.

### Modified Capabilities

- None. The repository has no existing normative capability spec for this
  behavior.

## Impact

- Rust: `ripdpi-desync-runtime`, `ripdpi-monitor-proxy-runtime`,
  `ripdpi-monitor-engine`, `ripdpi-diagnostics-candidates`, and
  `ripdpi-diagnostics-contracts`.
- Kotlin: `core:diagnostics`, diagnostics persistence/export, and diagnostics UI
  verdict projection.
- Contracts: diagnostics schema, Kotlin/Rust mirrors, field manifests, archive
  fixtures, and privacy allowlists.
- Verification: production SOCKS candidate runtime tests, Kotlin verdict tests,
  archive round-trip/privacy tests, and device evidence for RAW_PATH versus
  active-service IN_PATH behavior.
