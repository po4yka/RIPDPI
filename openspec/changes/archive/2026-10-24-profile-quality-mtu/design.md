## Context

Task `TRN-1792759596946903` extends the existing payload-aware selector. Its
coordinated producer is `po4yka/ripdpi-vpn-deploy#OPS-1792759217301702`.
Current candidate owners provide cancellation, configuration proofs and cleanup;
the periodic prober already owns network/policy/selection fences and native reload.
The native QUIC PMTU probe already measures separate raw IPv4/IPv6 paths.

## Goals / Non-Goals

- Goal: select a materially better encrypted relay using real bounded transfer
  measurements and expose useful partial facts and explicit decision reasons.
- Goal: keep traffic budgets durable and explain existing PMTU evidence honestly.
- Non-goal: another selector, a mandatory backend, direct fallback, automatic AWG
  switching or MTU changes, root requirements, or speed-derived MTU guesses.

## Decisions

### Frozen policy

Only `ripdpi.payload_failover.schema_version: 2` is accepted. Existing fields and
two distinct authenticated HTTPS origins remain. Each `expected_bytes` is an
integer from 32,768 through 1,048,576; SHA-256 remains exact lowercase hex.
`quality` is optional and, when present, has exactly these integer fields:

| Field | Bound |
|---|---|
| `max_pass_bytes` | 65,538 through 16,777,216; at least `2 * sum(expected_bytes + 1)` |
| `max_window_bytes` | 65,538 through 268,435,456; at least `max_pass_bytes` |
| `window_seconds` | 60 through 86,400; at least `interval_seconds` |
| `minimum_gain_percent` | 5 through 100 |
| `maximum_loaded_latency_increase_ms` | 0 through 2,000 |

The producer's example uses two 262,144-byte objects and quality values
2,097,152 / 16,777,216 / 3,600 / 20 / 50 respectively. Absence of quality keeps
health-only payload behavior. Absence of the whole extension keeps URLTest.
No field enables a new default background scan.

### Candidate measurement

Reuse the candidate's existing SOCKS endpoint and runtime/configuration owner.
An origin attempt runs a baseline HEAD, then a GET and concurrent loaded HEAD.
Each HEAD requires authenticated HTTPS, status 200, exact Content-Length, no
Content-Range, identity encoding and no redirects/retries/direct fallback.
HEAD reports HTTP transaction latency, not RTT. GET hashes a bounded body through
EOF. Its body-start timestamp comes from the first positive body read; body-end is
taken at EOF before digest finalization. Goodput is verified bytes per second over that monotonic
body interval, excluding native startup and handshake time.

Loaded HEAD starts only after body-start. Its entire measured interval must fit
before GET body-end, otherwise loaded latency stays unknown and goodput remains
visible. No artificial throttling or retry is used to manufacture overlap.
Each candidate tries at most the two admitted origins; a successful alternate
origin proves health, while quality comparison requires the same origin index
and digest from the same frozen policy. Baseline/loaded failures do not negate
an independently verified GET. A pass deadline includes runtime readiness,
bounded HTTP attempts and cleanup; cancellation retains ownership until release.

### Durable global application-body budget

Before any quality request, atomically reserve the worst allowed complete pass:
`2 * sum(expected_bytes + 1)`. This covers active/reserve and both origins, with
one overrun-detection byte per GET. Reservations never refund. HEAD has no body;
request count and header/deadline bounds are independently finite. This is an
application-body allowance, not a claim about TLS, TCP or IP-wire byte totals.

Use the established checked SharedPreferences commit pattern and `PauseClock`
facts (elapsed realtime plus BOOT_COUNT), under one app-wide budget owner. Persist
only version, boot count and bounded charge entries: monotonic time, bytes,
window and cap. Do not persist network, URL, credential or profile identifiers.
Retain entries for the maximum 86,400-second configured window so reimport or
window changes cannot erase consumption. Unexpired previous stricter policies
continue constraining reservations. Same-boot restart preserves ages; a reboot
conservatively rebases retained ages to the new boot's current time. Wall-clock
movement grants no credit. Unknown boot identity, malformed data, failed checked
commit or a 4,096-entry journal cap refuses I/O rather than resetting history.
An exhausted or unavailable budget is unknown evidence, never a health failure.

### Existing selector decision

When quality is enabled, measure the active member and one rotating reserve even
if the active member succeeds. The existing health state still handles verified
payload failure/recovery. Quality keeps a consecutive winning-reserve streak,
using `recovery_threshold` and existing cooldown. Incomplete observations reset
the quality streak. A complete comparable reserve qualifies by either:

1. verified goodput improves by at least `minimum_gain_percent`, and loaded
   latency is no greater than active latency plus the configured allowance;
2. positive loaded latency decreases strictly and by at least that percentage,
   while verified goodput does not decrease.

Only existing selection CAS/native reload applies a winner. Check network epoch,
policy/membership snapshot and manual-selection generation before publishing or
committing. Scope changes clear transient observations and decisions, not budget
history. All candidates failing retains the current encrypted selection.
Reality routes through `CandidateXrayPayloadProbe`; AWG remains outside this pool.

## Contracts and ownership

- Runtime-state owns schema-2 models/import and the durable budget plus observable
  `ProfileQualityStore.state: StateFlow<ProfileQualitySnapshot>`.
- A typed `ProfileQualityKey(groupId, profileId)` indexes observations containing
  verifiedBytes, bodyDurationNanos, goodputBytesPerSecond, baselineLatencyMillis,
  loadedLatencyMillis, loadedOverlap, status/reason and observedAtElapsedMillis.
  Missing facts are nullable, never zero placeholders. Internal measurement
  identity retains control index; UI receives no URL or digest.
- Snapshot budgets are keyed by group and expose reserved pass bytes, consumed
  global window bytes, cap, next eligible elapsed time and reason. Decisions are
  keyed by group and contain selected/challenger IDs, timestamp and one of
  CollectingEvidence, NoQualifiedImprovement, PromotedForGoodput,
  PromotedForLoadedLatency, Cooldown or DeferredBudget. Promotion decisions publish
  only after selection CAS succeeds under the current scope fence.
- Service owns HTTP metrics and relay/Reality lifecycle adapters. App subscription
  owns rounds, comparison, transient evidence, CAS and tests. One transport writer
  owns these paths in this worktree.
- The separate diagnostic/UI writer owns existing profile presentation, all ten
  locale sets, PMTU interpretation and architecture documentation. PMTU IP lower
  bounds add 28/48 bytes to acknowledged UDP sizes only for verified raw scope.
- The server writer freezes canonical schema and vectors before Android copies
  them through the existing golden discipline. No JNI/protobuf or Cargo changes.

## Risks / Trade-offs

- Fast GET may finish before HEAD: keep goodput visible, mark load unknown and
  decline quality promotion; health fallback remains usable.
- Different origins or network generations bias comparison: require the exact
  same admitted object and frozen scope before comparing or publishing.
- Persistence and reboot uncertainty: conservative reservation may defer work
  longer, but never silently reissues budget or converts uncertainty into failure.
- A shared budget can defer another selector: make the global usage and reason
  visible rather than creating independent allowances.

## Migration Plan

Schema 1 is rejected explicitly; the paired producer and all repository call sites
and contract fixtures move to schema 2. Existing ordinary subscriptions remain
valid, and schema-2 health-only use is not a compatibility path. No automatic
rewriting of externally supplied policy or secret data occurs. The encrypted local
catalogue upgrades previously accepted schema-1 health-only policies once, inside
its schema read boundary before typed decoding, for both stored group schema versions.
The existing mutex and checked durable write cover the complete upgrade, including
when the stored group schema is already current. It validates the complete
catalogue before writing, preserves profiles, credentials and ownership generations,
and adds no quality policy. Pending `subscription_refresh` journal after-images
use the same health-only upgrade before typed replay; envelope, provenance,
ownership and unrelated raw data are preserved. If an ownership upgrade is also
needed, both transformations share one checked journal replacement. Failed
migration writes publish nothing and retain the original bytes; external wire
schema 1 remains rejected. In-flight policy
changes invalidate observations while retaining charged allowances.

Validate contract/import and ledger tests first, then actual local HTTP/SOCKS
measurement tests, selector concurrency/decision tests, service ownership tests,
PMTU tests and UI/locale checks. Run real Android native candidate measurements
with owned controlled origins, including a true better-transfer selection and
partial loaded evidence, under the emulator lease. Run static analysis,
architecture/contract gates and protected PR/exact-main CI on the integrated
tree. Every heavy build uses build-gate with at most four workers; record native,
emulator and hosted evidence separately and never close from refusal-only tests.
