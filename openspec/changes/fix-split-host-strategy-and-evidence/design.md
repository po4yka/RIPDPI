## Context

The attached archive records `tcp: split(host+1)` and two complete
`baseline_current` candidate runs with no successful HTTP/TLS targets. It also
records partial success for plain direct. This proves that the ephemeral current
candidate did not restore those tested endpoints, but the archive has no proof
that the split plan resolved or executed and no active-VPN IN_PATH comparison.

The current implementation compounds that evidence gap:

- every scan session carries the current strategy snapshot and whole-report
  health is aggregated as if it evaluated that strategy;
- candidate summaries contain planned configuration and endpoint samples but no
  effective plan or action receipt;
- planning failure may fall back to a plain write with no exported distinction;
- the catalog `split_host` candidate is `host+2`, while the exact user strategy
  is tested only by `baseline_current` as `host+1`;
- strategy candidate bases retain route-stack features that can alter the
  effective path;
- runtime terminal errors are reduced to cleanup counts and may look like
  network failure.

The design must add useful evidence without putting raw packet material or
unbounded callbacks on the outbound hot path.

## Goals / Non-Goals

- Goal: prove whether the exact configured desync plan was selected and applied
  before interpreting the remote endpoint result.
- Goal: isolate current-strategy verdicts from unrelated paths and candidates.
- Goal: preserve launch, activation, planning, fallback, execution, runtime,
  socket-write, HTTP, TLS, QUIC, and DNS outcomes as separate typed axes.
- Goal: export generation-correlated, bounded, privacy-safe evidence through the
  Rust/Kotlin/archive contracts.
- Goal: test the production local SOCKS candidate runtime rather than only the
  direct test double.
- Non-goal: claim that one captured path generalizes to the user's production
  VPN path or every destination.
- Non-goal: capture raw ClientHello, domains, addresses, payload bytes, packet
  traces, credentials, interface names, or stable network identifiers.
- Non-goal: choose or promote a new traffic variation solely from this archive.
- Non-goal: add evidence callbacks to the existing broad
  `RuntimeTelemetrySink` or invoke a callback for every action.

## Decisions

### 1. A typed return value and narrow proxy evidence port own the hot-path boundary

The desync runtime builds one scalar receipt locally for the first outbound
payload and returns it through `OutboundSendOutcome` or `OutboundSendError`.
Planner and action internals remain private. A separate optional one-method
`DesyncExecutionEvidenceSink` lives beside `EmbeddedProxyControl` in
`ripdpi-runtime-api`; the proxy calls it once after the returned send outcome is
known and outside internal locks. `PcapHook` is rejected because it handles raw
bytes, runs for each write, and increases both contention and privacy risk.

The receipt contains only:

- typed disposition;
- configured and effective strategy family;
- marker base and bounded delta, plus whether a resolved offset exists;
- bounded plan/action/write/await/byte counts;
- typed fallback or terminal reason;
- candidate generation and monotonic receipt revision supplied outside the hot
  path.

The evidence does not include candidate ID inside the stack-local receipt;
`ripdpi-monitor-proxy-runtime` binds it to the candidate generation before it
crosses into the monitor engine.

Candidate generation alone is not an attempt join key. One ephemeral runtime
serves warm-up traffic and multiple HTTP/HTTPS samples concurrently, while a
single HTTPS sample can open several TLS connections. Each logical sample is
therefore assigned a bounded opaque `AttemptToken`. The diagnostics transport
carries that token in the username of the existing loopback-only SOCKS5 RFC
1929 exchange, while a per-runtime random secret remains the password. The
proxy strips the token at the local handshake boundary and binds every
first-write receipt to `{ candidateGeneration, attemptToken,
connectionOrdinal }`. Warm-up traffic uses an explicit non-evaluable role.
Timestamp, destination, domain, address, local port, and payload joins are
forbidden. Starting one proxy runtime per attempt is rejected because it would
change the measured path and add launch/warm-up cost; a candidate-wide receipt
is rejected because it cannot identify which concurrent sample executed it.

### 2. Execution and endpoint outcome are independent

`APPLIED` means the effective actions and real writes completed as evidenced; it
does not mean the endpoint replied. HTTP status, TLS ServerHello, QUIC response,
timeout, EOF, and connection failure stay in the existing probe-result axis.
Planner failure followed by a plain write becomes
`PLAN_FAILED_PLAIN_FALLBACK`, never applied split evidence.

### 3. Candidate shutdown returns an authoritative terminal receipt

Replace cleanup-only candidate shutdown results with
`CandidateRuntimeTerminalReceipt { generation, cleanup, shutdown_mode,
worker_outcome, receipts, overflowed }`. Both normal shutdown and forced abort
return the same typed contract. Worker errors and panics remain terminal runtime
failures even when cleanup succeeds. Receipts are bounded and correlated by
attempt token; overflow, unknown tokens, and late receipts make the affected
attempt unverified rather than applied.

### 4. Catalog candidates use an isolated base

Create an explicit candidate-path builder that clears route stack state not
owned by the candidate: relay, WARP, WebSocket tunnel, upstream routing,
rotation, adaptive/evolution state, and unrelated activation filters. Features
that cannot be cleared for a valid test must be represented by privacy-safe
effective-path categories in the receipt. `baseline_current` remains a snapshot
of the current profile but its verdict describes only that compound snapshot,
not one action in isolation.

### 5. Verdict evaluation is typed and candidate-scoped

Replace whole-report `all(results healthy)` attribution with a pure evaluator.
It accepts report completion, `baseline_current`, observation path, strategy
snapshot identity, terminal receipt, execution disposition, and endpoint
samples. It emits:

- `WORKING`;
- `INEFFECTIVE_ON_TESTED_CANDIDATE_PATH`;
- `UNVERIFIED_EXECUTION`;
- `INCOMPLETE_EVIDENCE`;
- `ACTIVE_PATH_UNVERIFIED`.

Only complete, same-snapshot, `APPLIED` `baseline_current` evidence with at
least one attempted endpoint can produce the first two states. RAW_PATH generic
connectivity and other strategy candidates do not participate.

### 6. Observation roles are explicit

All strategy evidence carries one allowlisted role:
`EPHEMERAL_CANDIDATE_RAW_PATH` or `ACTIVE_SERVICE_IN_PATH`. The existing path
comparison stage must either execute through an authoritative owned VPN/proxy
path or record a typed reason it was unavailable. Enabling active VPN IN_PATH
execution is gated on the existing owned-route authority; absence of that proof
does not become a strategy failure.

### 7. Contract evolution is deliberate and breaking

The Rust diagnostics engine contract changes from schema 8 to 9. Update
`EngineContract.kt`, Rust wire models/conversions, fixtures, API snapshots,
field manifests, and all producers/consumers together. The diagnostic archive
format changes from 10 to 11 with explicit golden blessing and v10 compatibility
fixtures. No parallel legacy path or compatibility shim is retained in runtime
code; stored v10 archives decode with missing new evidence as
`UNVERIFIED_EXECUTION`.

### 8. The existing partial writer diff is not the implementation

A display note such as `strategyPlan=tcp: split(host+1)` is useful planned-plan
context but is not execution proof. It may be retained only as a derived label
from typed fields. It cannot drive verdicts and arbitrary string notes cannot
cross the archive privacy boundary.

## Contracts and ownership

- `ripdpi-desync-runtime`: constructs and returns the stack-local execution
  receipt after plan selection and send completion.
- `ripdpi-runtime-api`: owns the narrow optional execution-evidence port carried
  by `EmbeddedProxyControl`.
- `ripdpi-proxy-runtime` and `ripdpi-proxy-runtime-desync-adapter`: preserve the
  typed send receipt, extract the loopback-only attempt tag, and publish once
  after first-write completion.
- `ripdpi-diagnostics-transport`: carries the opaque attempt token in the
  authenticated ephemeral SOCKS transport without exposing its password in
  `Debug` or diagnostics output.
- `ripdpi-monitor-proxy-runtime`: owns the optional evidence sink, candidate
  generation and attempt-token binding, bounded per-attempt collection, runtime
  terminal receipt, and late-receipt rejection.
- `ripdpi-diagnostics-candidates`: owns isolated candidate construction and the
  exact distinction between `baseline_current host+1` and catalog
  `split_host host+2`.
- `ripdpi-monitor-engine`: owns attempt correlation, endpoint-stage projection,
  summary construction, and promotability rules.
- `ripdpi-diagnostics-contracts`: owns Rust wire enums/DTOs and engine schema 9.
- `core:diagnostics`: owns Kotlin mirrors, pure current-strategy evaluator,
  persistence, archive redaction/allowlists, and archive schema 11.
- `app`: owns wording and visual projection only; it does not infer missing
  execution evidence.
- Serialized shared files: Rust/Kotlin engine schema constants, wire fixtures,
  native API snapshots, diagnostics field manifests, archive manifests,
  integrity fixtures, and schema-11 golden family. A single writer owns these
  files during implementation.

## Risks / Trade-offs

- Hot-path regression from evidence collection -> one stack-local scalar receipt
  and one optional post-send callback; benchmark and verify no raw-byte clone.
- Cross-generation misattribution -> immutable generation plus monotonic
  revision and rejection tests for late cancellation events.
- False certainty from planned configuration -> verdict requires effective
  `APPLIED` receipt, not a note or config JSON.
- Candidate isolation changes historical scores -> expected breaking behavior;
  invalidate learned/promoted results lacking schema-9 applied evidence.
- Schema/golden churn -> serialized-file ownership, explicit blessing, semantic
  review, compatibility fixtures, and whole-ZIP privacy scan.
- Active VPN IN_PATH probe may be unavailable on some devices -> preserve
  `ACTIVE_PATH_UNVERIFIED` with an allowlisted reason; never silently substitute
  RAW_PATH.
- More conservative verdicts reduce apparent coverage -> acceptable because
  unverified evidence must not be credited as strategy success or failure.

## Migration Plan

1. Add red tests for execution dispositions, production candidate runtime,
   generation correlation, isolation, pure verdict evaluation, and archive
   privacy/compatibility.
2. Introduce typed Rust receipts and the narrow optional sink without changing
   verdict consumers.
3. Return terminal receipts from candidate shutdown and propagate them through
   the monitor engine.
4. Isolate catalog candidate configuration and record effective route-feature
   categories for `baseline_current`.
5. Bump engine schema 8 to 9 and update Rust/Kotlin wire contracts, fixtures,
   API snapshots, and field manifests atomically.
6. Replace whole-report attribution with the candidate-scoped evaluator and
   update UI wording.
7. Bump archive schema 10 to 11, add v10 decode coverage, run hostile whole-ZIP
   privacy tests, and bless only the schema-11 golden family with explicit
   approval.
8. Verify active-service IN_PATH behavior on a supported physical device; keep
   `ACTIVE_PATH_UNVERIFIED` until owned-route and execution evidence correlate.

Rollback is a normal code rollback before publication. Once schema 9/11 output
ships, rolling back the producer would create incompatible evidence, so release
must be gated on Kotlin/Rust contract, archive compatibility, privacy, device,
and artifact verification. Previously learned candidates without applied
execution receipts are not migrated as successful evidence; they are
reclassified as unverified and must be evaluated again.
