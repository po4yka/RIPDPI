## Context

Task DGN-1791525730340153 adds local evidence to existing DiagnosticContextModel snapshots. Context is captured before/following scans and during runtime, then persisted as JSON. Cellular metadata currently depends on the active transport.

## Goals / Non-Goals

- Goal: collect and explain local settings and default-data SIM state with explicit evidence limits.
- Non-goal: add probes, infer balance or provider policy, change Android settings, request new permissions, or modify fingerprint identity.

## Decisions

- Add optional `localNetwork: LocalNetworkContextModel?` with `EncodeDefault.NEVER`. Nested device/SIM models contain enum values only, plus nonnegative capture time and coarse active transport.
- New AndroidLocalNetworkContextCollector uses an injectable platform seam. Each API failure maps to permission_denied, unsupported or unavailable. No raw errors or identifiers enter the model.
- Resolve default-data subscription explicitly, use createForSubscriptionId, compare the selection before/after capture and discard mixed SIM evidence on a change. The ID is transient. Expose only whether active data uses the same subscription.
- Collect independently of Wi-Fi/VPN/no-network. Default-data SIM facts are labelled as mobile context, not as properties of the scan path.
- Pure LocalNetwork assessment emits fixed reason codes. UI and exports share these codes. A setting is an observation, not proof that it caused a scan failure.
- Reuse DiagnosticsUiContextGroups for current/live/history evidence. All new labels and explanations ship in ten locales. Existing context cards handle wrapping and scrolling.
- Preserve optional evidence through persistence and redacted JSON/text export. Fixed enum fields avoid raw value copying.

## Contracts and ownership

- Integration writer owns model/assessment files, DiagnosticContextModel optional field, export integration, documentation and task records.
- Collector writer owns new AndroidLocal* files/tests and DiagnosticsContextProvider.kt in a separate worktree.
- UI writer owns app mapping/tests and all locale resources in a separate worktree.
- No native, JNI, Room, fingerprint, dependency, manifest, golden or baseline changes. Gradle runs are serialized.

## Risks / Trade-offs

- OEM API errors and permission revocation are partial observations, not failed scans.
- Invalid default-data ID can mean no selection or a platform failure. Its message must not claim no physical SIM.
- `ServiceState.state` describes voice registration. Store it as `voiceServiceState`; do not infer a mobile-data failure when voice service is unavailable.
- Android API 27 does not expose background restriction state. Return unsupported before API 28. Newer telephony getters have separate version and permission guards.
- No dedicated local/SIM RDS layout is defined. Reuse context cards with full-width stacked labels and values so long explanations remain readable in history, RTL and large text.
- No settings mutation or network request is performed by this collector.

## Migration Plan

Old contexts decode with null local evidence and retain their export shape. New categorical values are bounded; no database migration is needed. Rollback removes the optional projection. Gates: collector/assessment/compatibility/privacy tests, app localization/Compose tests, full diagnostics unit suite, staticAnalysis, app/service locale lint, architecture health, locked Cargo metadata, task validation and independent review. Device, APK and hosted CI acceptance remain distinct evidence lanes.

## Platform sources

- https://developer.android.com/reference/android/telephony/TelephonyManager
- https://developer.android.com/reference/android/telephony/SubscriptionManager
- https://developer.android.com/reference/android/net/ConnectivityManager
- https://developer.android.com/reference/android/os/PowerManager
