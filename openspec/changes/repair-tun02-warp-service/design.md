## Context

Task ID: `TRN-1794384538458949`. Audit baseline: ec4f13320a.
Three isolated writers follow the portfolio ownership lanes. Each finding
remains a separate fix commit on the integration branch.

## Goals / Non-Goals

- Goal: correct supported WG setup, account state, endpoints, and runtime.
- Non-goal: remote license activation, browser/team enrollment, MASQUE,
  new production dependencies, or unapproved real-account/device probes.

## Decisions

- F1 reuses handshake primitives with stop-aware bounded startup and peer
  expiry failure. Listener readiness alone is not WARP readiness.
- F2 reuses RelaySocketProtection, serialized inactive/vpn_required, on resolved
  runtime, native, and probe DTOs. Missing wire policy defaults VpnRequired.
  Production callers map explicit RelayRuntimeNetworkMode, not callback
  presence or protectPath. Android maps to native SocketProtectionPolicy.
  User probes hold a scoped inactive reservation or a captured VPN owner.
  VPN startup rejects before creating routes while inactive probes exist.
  A request-local JNI controller protects each probe socket and rejects a
  withdrawn owner; it never replaces the runtime callback registry.
  The existing one-String JNI probe ABI remains and fails closed without a
  request owner. A distinct WithController entrypoint serves current callers.
- F3 passes current networkScopeKey through composition, stack, supervisor,
  and resolver. A frozen scan context holds credentials and config. Runtime
  scans probe cached endpoints and do not accept unvalidated fallbacks when
  scanning is enabled. Matching revision/CAS alone may persist the selection.
  Complete keys do not require consumer HTTP merely for endpoint selection.
- F4 strips numeric socket-address ports in Kotlin and Rust, retaining the
  service port and rejecting malformed numeric fields before system DNS.
- F5 local attach/remove retain verified kind. Server account state can update
  kind during refresh; same-profile metadata reconcile updates UI settings.
  Existing revision checks protect later edits and local license/name data.
- F6 separate GET/POST DTOs retain existing GET token. Provider bodies never
  enter user/log errors. POST still requires a valid token.
- F7 an optional endpoint field preserves source compatibility, but validation
  rejects incomplete material before mutation. Valid import replaces old
  endpoints and cannot use the consumer refresh path.
- F8 adds a small service facade, separate setup ViewModel, explicit consumer
  registration confirmation, and SAF ready Zero Trust JSON import. Schema v1
  accepts at most 64KiB UTF-8; fields include displayName, organization,
  deviceId, accessToken, privateKey, publicKey, peerPublicKey, IPv4 interface,
  and endpoint. Optional profileId/clientId/IPv6/refreshToken stay private.
  A dedicated engine keypair binding avoids changes to the runtime mapper.
  Native keypair generation uses existing X25519/random/base64 dependencies
  for API27. No request starts on composition. Busy/cancelling tracks actual
  blocking provisioning completion and never promises remote rollback.

- F9 moves the three 64 KiB probe packet buffers to heap storage. Packet
  sizes, protocol output, timeout, and ownership do not change. A real loopback
  regression uses a 256 KiB worker stack; no larger stack setting is used.

## Contracts and ownership

- Native writer: warp-core runtime/tunnel/config, warp-android protection and
  lifecycle/probe adapters, F2 engine API/mapper. Control writer supplies F2
  service propagation and Kotlin F4 changes for the same finding commits.
- Control writer: existing WARP service resolver/scanner/enrollment/mutation,
  mode/scope callers, and data account-state merge.
- Enrollment writer: app, all ten locales, translation export, NEW service
  facade, NEW native keypair modules and lib exports. Engine mapper edits are
  use a dedicated new file; no concurrent writes to the runtime mapper.
- Coordinator: task/spec artifacts, generated contract/API snapshots,
  combined gates, review, commits, main integration, and push.
- No proto, dependency, lockfile, golden, or quality-baseline change is planned.
  The F2 guarded probe and F8 keypair exports add exactly two inventory entries.
  The user approved both additions after comparison with the packaged ELF.
  The owning checker passed. Preserve the old probe ABI and compare the
  rebased ELF with the same approved inventory; do not infer symbols.

## Risks / Trade-offs

### Integration with the shared WireGuard repair

Upstream through fe82ae70b8 adds address-family fallback, Allowed IPs,
authenticated endpoint roaming, and non-blocking runtime shutdown. Keep
these changes when rebasing the WARP repair. Compose numeric endpoint
normalization into the candidate resolver; do not replace either contract.

The backend retries after five seconds plus jitter. A four-second WARP
handshake budget prevents silent-primary fallback. Use a bounded 12-second
native handshake budget and a 25-second readiness budget, which covers the
10-second resolver bound, handshake, and margin. The selector reload has a
45-second outer bound. This permits normal two-family fallback; it does not
promise exhaustive retries for an arbitrary address list or custom AWG timers.
Test the real runtime with a silent IPv4 primary and authenticated IPv6 peer
at the same configured port. Keep peer proof and expiry under the peer lock.
Re-run affected host and packaged Android checks after this integration.

- Startup timing changes: real loopback positive/negative peer tests cover
  deadlines/cleanup and shared AWG suites prevent cross-surface regression.
- Protection defaults must stay fail-safe; inactive and rejected/missing
  required callback tests cover both modes.
- Endpoint races: frozen material and CAS protect concurrent profile edits.
- Cancellation cannot revoke a remote registration; UI must not claim it does.
- New UI needs locale parity and rendering. No golden blessing is authorized.

## Migration Plan

No durable schema migration is required. Existing credentials stay encrypted.
Incomplete saved profiles require repair/import; a stored license is no longer
proof of Plus. Endpoint forms normalize on selection. Rollback uses ordinary
revert commits, never published history rewriting.

Run focused red/green tests; affected Rust tests and Clippy with --locked;
affected Kotlin tests; staticAnalysis; locale lint; task-check; ci-gates;
architecture health; locked metadata; contract snapshots; app compilation.
Use an owned emulator lease for UI/JNI acceptance when available. Keep local,
device, artifact, hosted CI, and external compatibility evidence distinct.
Archive only after required evidence is complete.
