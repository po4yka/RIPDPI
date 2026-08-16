## Context

RIPDPI's own package is deliberately excluded from every normal VPN app-routing
shape. On Android 16, `ConnectivityManager.activeNetwork` is the default network
for the calling UID, so the owner process correctly sees its physical underlay.
The current implementation nevertheless uses that value as the VPN existence
oracle in both live UI evidence and persisted network snapshots.

The user archive provides a direct contradiction at one timestamp: the
snapshot path reports Wi-Fi, `NOT_VPN`, and `vpnPresent=false`, while the
network-transition stream reports an owned VPN with Internet and validation,
followed by generation-bound `cross_layer_return_observed` TUN evidence. The
fix therefore needs to reconcile already available Android, service-lifecycle,
and native-forwarding evidence rather than infer health from relay traffic.

Android public APIs do not expose another arbitrary UID's default network.
Consequently, this design can prove that the RIPDPI-owned VPN exists, which
route families Android installed, what app-routing shape RIPDPI requested, and
whether TUN traffic crossed the data plane. It cannot claim that every client
application is routed without a separate client UID/device test.

## Goals / Non-Goals

- Goal: remove the owner-process `activeNetwork` false negative from live Route
  state and exported snapshots.
- Goal: correlate observed Android VPN routes with the current builder/lifecycle
  receipt and preserve native forwarding as a separate evidence axis.
- Goal: make archives distinguish observer context, missing VPN, route mismatch,
  validation failure, incomplete startup, and forwarding failure without raw
  identifiers.
- Goal: keep startup, rebuild, handover, late callback, fail-closed, and teardown
  transitions generation-safe.
- Non-goal: change split-tunnel policy, relay selection, encrypted DNS, DPI
  strategy, or the websites' actual reachability behavior in this change.
- Non-goal: reinterpret local SOCKS or relay success as proof that Android routed
  a third-party app through the TUN.
- Non-goal: enable the existing SOCKS in-path scan in VPN mode; production
  active proof from a distinct client UID remains a separate capability.
- Non-goal: change JNI, protobuf settings, Rust diagnostics-engine wire types,
  dependencies, or public configuration.

## Decisions

- Use two independent authorities and require coherent correlation:
  - A service-owned lifecycle receipt is authoritative for RIPDPI ownership,
    builder intent, TUN establishment, app-routing shape, bridge readiness, and
    teardown.
  - A callback-backed VPN observer is authoritative for the OS-observed VPN
    network's existence, capabilities, validation, and installed link routes,
    but it is not called RIPDPI-owned until correlated to the current receipt.
  Native forwarding evidence remains a third, separate axis.
- Put the route-authoritative VPN callback state machine in `:core:service`,
  alongside the lifecycle receipt that identifies the current RIPDPI VPN
  session. Expose one Android-type-free, privacy-safe provider through
  `:core:data:model`; `:core:diagnostics` consumes that provider for live and
  archived route evidence. The existing diagnostics callback may remain as a
  transition timeline, but it is not an ownership or installed-route oracle.
- Build the VPN request with public APIs to match `TRANSPORT_VPN` after clearing
  the default `NOT_VPN` capability. On supported API levels, include other-UID
  networks and accept only a VPN whose `ownerUid` is RIPDPI's UID. On older API
  levels, accept only a VPN callback observed while the current service-owned
  establish receipt is live; mark owner verification unavailable rather than
  claiming a foreign VPN is RIPDPI-owned.
- Keep the calling-UID default network as an explicitly named contextual
  observation (`calling_uid_default`). It may explain why diagnostics sockets
  use the underlay, but it cannot set `vpnPresent` or the VPN Route verdict.
- Maintain callback state per ephemeral `Network` key. Publish a complete,
  service-correlated VPN observation only after capabilities and link
  properties for that key are present. `onLost` removes only its own key; a
  late loss from an old key cannot clear a replacement. No raw network handle
  crosses the provider boundary.
- Add a service-owned `VpnRouteLifecycleReceipt` rather than changing the
  meaning of the existing ready-only `VpnTunnelAppliedNetworkReceipt` in place.
  The receipt uses an ephemeral lifecycle generation and transitions through
  intended, established, bridge-ready, fail-closed, and closed states. It
  records only route/address/DNS families, categorical app-routing shape,
  bounded app count, own-package exclusion, IPv6 intent, MTU band, and metering.
- Publish `established` immediately after `Builder.establish()` returns a live
  session, before native bridge readiness. Preserve the established/fail-closed
  receipt while the TUN session remains open; publish `closed` only after the
  descriptor is actually closed. Update bridge readiness and forwarding lease
  state without allocating a new lifecycle generation.
- Assign one lifecycle generation per builder-establish lifecycle and a separate
  callback revision for each coherent capabilities/link-properties shape.
  Handover or validation changes advance the callback revision without creating
  a new lifecycle generation. Correlate a VPN callback observation to the
  current receipt generation
  only after it arrives in the receipt's lifetime and passes owner filtering.
  A missing or mismatched generation yields checking/unverified evidence, never
  a fabricated failure. Rebuild creates a new receipt generation before the old
  callback key is retired.
- Keep existing serialized `pathSnapshots.vpn` and association semantics as
  legacy calling-default observations. Add a separate optional nested
  `vpnRouteEvidence` object for service-correlated VPN presence and route
  provenance instead of repurposing the old `active_default` path. Existing
  compatibility booleans may be derived from service-correlated evidence for
  current live consumers, but legacy JSON remains decodable and is never
  rewritten as if it came from the new observer.
- Project UI state with a pure classifier:
  - `NotApplicable`: service is not Running in VPN mode.
  - `Unverified`: permission/callback/owner verification is unavailable,
    including expiry of the bounded callback convergence window without a
    positive or negative owned-VPN observation.
  - `Checking`: a current establish receipt exists but a coherent callback and
    route snapshot have not arrived within the startup convergence path. This
    transient state is informational and does not produce a degraded Route
    warning inside the bounded convergence window.
  - `Unavailable`: current establishment failed or the current owned VPN was
    authoritatively lost after convergence.
  - `Degraded`: the service-correlated VPN exists but intended default-route
    families are missing.
  - `Working`: the current service receipt, callback observation, and installed
    route families are coherent. Android validation/captive-portal failures are
    projected through the Network axis, while native forwarding failures stay
    on the existing Tunnel/data-plane axis; neither is rewritten as an absent
    or degraded installed Route.
- Export these bounded fields in snapshots/redacted summaries: observer role
  and source, callback completeness, owner-verification category, evidence-age
  band, observation and lifecycle generations, intended/observed route
  families, route consistency, lifecycle state, categorical app-routing shape,
  bounded app count, own-package-excluded, Android validation, and existing
  generation-bound forwarding outcome. Never export `Network`, interface,
  package, UID, address, endpoint, SSID/BSSID, or stable identity values.
- Do not restore stage-scoped telemetry or add a companion client APK in this
  change. Both are useful follow-ups, but neither is required to correct the
  false Route verdict or make its causal evidence exportable.

## Contracts and ownership

- `:core:data:model` owns privacy-safe, Android-type-free
  `VpnRouteLifecycleReceipt`, `VpnRouteEvidence`, and provider contracts shared
  across modules.
- `:core:service` owns the route-authoritative VPN callback state machine,
  receipt/callback correlation, and receipt transitions at TUN establish,
  bridge ready, fail-closed retention, rebuild, and actual descriptor close.
  Existing `DataPlaneEvidenceCollector` remains authoritative for forwarding
  outcomes.
- `:core:diagnostics` consumes the provider and owns evidence projection,
  persisted snapshot models, redaction, and archive serialization. It does not
  independently infer route ownership from `ConnectivityManager`.
- `:app` consumes the assessed evidence and owns only UI mapping. It does not
  query `ConnectivityManager` or infer route truth from relay counters.
- No Rust crate changes are planned. `DIAGNOSTICS_ENGINE_SCHEMA_VERSION`, JNI
  signatures, protobuf settings, and native wire fixtures remain unchanged.
- `NetworkPathValidationEvidence` and `NetworkPathSnapshotPair` are serialized
  persistence/archive contracts. Add optional `vpnRouteEvidence` fields with
  defaults so old snapshots decode, and bump the diagnostics archive schema
  from 9 to 10. Update only the exact schema-10 fixture family through its
  governed workflow.
- Archive fixtures/goldens are serialized high-risk files with one writer.
  Blessing is forbidden until the user explicitly authorizes the exact affected
  fixture family after reviewing the generated diff.
- No locale change is planned. If implementation requires new user-facing text,
  all nine locales and both app/service lint gates become part of the same
  serialized writer lane.

## Risks / Trade-offs

- Callback ordering can expose partial capabilities/link properties -> publish
  a coherent store snapshot only after both are present; classify convergence
  as checking.
- A callback can observe another VPN -> filter by public owner UID where
  available and require a current service receipt on older APIs; otherwise
  report unverified.
- Receipt generation and callback revision are not Android-shared IDs -> let the
  receipt establish ownership, correlate by current lifecycle and event order,
  invalidate on close, and reject ambiguous overlaps.
- VPN validation may transiently disappear during handover -> retain it as
  coherent callback provenance for the Network axis; do not degrade installed
  Route state, and never let an old loss clear the replacement.
- Adding serialized fields can break goldens or old readers -> keep fields
  additive/defaulted, run legacy round trips, and stop for explicit fixture
  blessing if the governed expected output changes.
- TUN/relay counters can grow while an app is intentionally bypassed -> keep
  app-routing shape and forwarding evidence separate and avoid per-app claims.
- The archive's observed callback proves the root cause, but not the separate
  website failure -> keep DNS/split-host/protocol investigation out of the fix
  and state that residual explicitly.

## Migration Plan

1. In an isolated implementation worktree, add failing service tests and then
   implement the Android-type-free receipt/provider contract plus the
   service-owned callback state machine for establish, bridge-ready,
   fail-closed, rebuild, handover, and close transitions. Keep the old
   applied-network receipt until all consumers move to the explicit contract.
2. Add failing diagnostics and app projection tests for `calling default =
   Wi-Fi` plus a service-correlated VPN, callback convergence, route mismatch,
   validation-only failure, forwarding-only failure, and authoritative loss;
   then consume the provider and update UI projection one behavior at a time.
3. Add the optional `vpnRouteEvidence` archive projection, schema-10 migration,
   redaction, and legacy round-trip tests. Run the relevant golden tests
   without blessing; if intentional fixture output changes, present the exact
   diff and request fixture-family authorization before any bless command.
4. Run targeted module tests, app projection tests, `staticAnalysis`,
   architecture health, and task-board validation. Report each gate exactly.
5. On a physical API 36 device, verify self-excluded owner default, owned VPN
   callback visibility, route families, start/rebuild/handover/stop transitions,
   absence of the false Route warning, and a third-party traffic/TUN counter
   correlation. Treat this separately from local and hosted-CI evidence.

Rollback is a normal code revert: optional archive fields remain decodable, the
callback registration is unregistered with its lifecycle, and receipt state is
in-memory only. No database or protobuf migration and no persistent backfill are
required. Do not archive or close the portfolio task until local gates and the
required device evidence are recorded; hosted CI remains separately unverified
unless observed.
