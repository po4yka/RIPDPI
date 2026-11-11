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
  Native keypair generation uses existing X25519/random/base64 dependencies
  for API27. No request starts on composition. Busy/cancelling tracks actual
  blocking provisioning completion and never promises remote rollback.

## Contracts and ownership

- Native writer: warp-core runtime/tunnel/config, warp-android protection and
  lifecycle/probe adapters, F2 engine API/mapper. Control writer supplies F2
  service propagation and Kotlin F4 changes for the same finding commits.
- Control writer: existing WARP service resolver/scanner/enrollment/mutation,
  mode/scope callers, and data account-state merge.
- Enrollment writer: app, all ten locales, translation export, NEW service
  facade, NEW native keypair modules and lib exports. Engine mapper edits are
  serialized after F2; no concurrent writes to that file.
- Coordinator: task/spec artifacts, generated contract/API snapshots,
  combined gates, review, commits, main integration, and push.
- No proto, dependency, lockfile, golden, or baseline edit is planned. Owning
  scripts regenerate affected JNI/DTO snapshots rather than manual edits.

## Risks / Trade-offs

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
