## Context

The approved research defines seven sequential feature slices. Existing RDS controls, diagnostic path enums, telemetry, profile stores, export redactors and protected payload URL-tests provide the base. Discovery found missing scan explanations, technical path enum display, unused health timestamps, saved-setting facets presented as active, absent picker search, direct export launch, and no durable timed pause.

## Goals / Non-Goals

- Goal: deliver all seven behaviors with factual source-derived UI and explicit failure states.
- Goal: retain Android offline/non-root operation and user-controlled exports.
- Non-goal: geography maps, synthetic scores, fabricated router measurements, commercial subscriptions or a new backend.

## Decisions

- Preserve RAW_PATH/IN_PATH as machine data; localize presentation and explain the actual direct-path interruption of VPN or proxy without unconditional restoration promises. Manual restoration follows the auto-resume setting (enabled in app defaults); strategy trials are isolated measurements. In-path admission on Android requires a running RIPDPI runtime; VPN mode additionally requires an eligible active route lease and proxy mode checks its local listener against the planned endpoint.
- Publish measurement timestamps/windows from the owning service DTO. Do not reuse the five-minute connection-health window for cumulative DNS/latency aggregates.
- Capture an immutable applied configuration summary in service ownership when the runtime starts; compare saved settings to the applied summary. Do not infer applied config from a UI observation of Connected.
- Reuse diagnostic family grouping and existing relay types. Search is local/saveable; filtering or closing never changes selection.
- Prepare immutable actual export content before external launch for diagnostic summary/archive, Home analysis, and Settings/Logs exports. Preview the final archive summary.txt, actual inventory and byte size from the prepared ZIP; text-only logs retain their actual text/plain contract. Apply typed redaction before summary projection and capture external runtime context once. Preserve schema 12 and the fixed redacted_unlinkable_v2 policy. Confirmation consumes one prepared generation and SAF copies its exact bytes, without reloading Room. Cancel, replacement and late preparation results discard only their owned artifact and ExportRecord under archiveMutex, preserving cancellation and reporting cleanup errors. Keep normal committed-archive DATABASE recovery intact. Checked app-private managed leases survive recreation/process restart, exclude unexpired artifacts from count retention, and expire no later than the existing three-day maximum using same-boot elapsed time as well as wall-clock checkpoints. Detected backward wall-clock changes, changed boot identity or unavailable clock proof invalidate the old lease; a fresh export can be prepared when the clock proof is available. The private journal preserves the latest clock checkpoints across concurrent phase writes. The read-only FileProvider revalidates confirmed lease identity, expiry, actual bytes and archive integrity without initializing Room or Hilt; chooser launch retains eligible files for delayed URI reads.
- Implement durable pause using persisted generation/mode/deadline and service-owned scheduling/reconstruction. Retain the existing specialUse foreground service with a paused notification while releasing native runtime/TUN, and use permissionless setAndAllowWhileIdle alarm delivery plus sticky reconstruction. State that Android may delay idle delivery; never promise exact wakeup. Confirm shell teardown/boot ownership before slice six edits. An activity coroutine or unbounded delayed worker is insufficient. Android references: https://developer.android.com/reference/android/app/Service#START_STICKY and https://developer.android.com/reference/android/app/AlarmManager#setAndAllowWhileIdle(int,long,android.app.PendingIntent).
- Pause resumes the chosen mode using current saved settings, with this effect stated in the UI. Ordinary saved configuration/profile edits retain the pending pause. Explicit Start/Stop, profile activation/deletion and reset supersede it. Persist the typed pause intent before teardown; a persistence failure keeps the active connection. Reconstruct a paused foreground shell without native/TUN/selector/probe activity. Clear the consumed pause only after positive runtime applied acknowledgement; denied consent, stale intent, cleanup failure and delayed OS delivery remain factual states. A stale deadline callback cannot restart a newer user intent. Boot handling must honor OS eligibility and show deferred/unavailable recovery honestly.
- Favorite/recent metadata is separate from secret-bearing profile configurations. Record successfully used recents only from positive runtime applied acknowledgement, with guaranteed publication and the mandatory durable consumer introduced together. Saved selection, pending activation and failed probes do not count. Use typed relay IDs or selector group/member ID pairs, reactive committed catalog generations, and prune references on deletion/reset.
- Expose the existing measured URL-test selector capability, including visible/configurable probe URL, actual member latency and observation time; label it lowest measured HTTP response time. Retain existing scope/revision fencing and protected candidate cleanup. Unsupported/all-failed results preserve the current valid selection.
- Remove synthetic active-profile/failover assertions in the touched selector UI: require exact profile ID match and actual event evidence.

## Contracts and ownership

- `:app`: UI, ViewModels, profile metadata/selector presentation, resources in all ten locales.
- `:core:diagnostics`: presentation/export payload preparation and existing redaction policies.
- `:core:service`: applied summary, health DTO metadata, durable pause orchestration and candidate probes.
- `:core:data:runtime-state`: persisted pause intent and boot/process recovery; other data modules only if their owning repositories require additive fields.
- Scope route correlation requires one private JNI facade addition to read the live TUN kernel interface identity; keep it in memory and preserve generation/owner fencing. The user explicitly approved its one-symbol compiled baseline update on 2026-10-05; source/native acceptance remains required. No serialized public wire/protocol change is planned. Serialize any needed protobuf/storage schema changes through the one active feature writer and update every consumer in that slice.
- Root exclusively owns OpenSpec/task state and integration. Each slice has one implementation writer in a dedicated worktree; review agents never modify files. Locale resources and persistence schemas are serialized.

## Risks / Trade-offs

- Android foreground-service, VPN consent, sticky restart and boot restrictions: use existing ownership/notification contracts, verify denied-start and reconstruction paths, and obtain emulator/device evidence when available.
- Export preview races and secrets: preview exact prepared content; fence session changes, clear cancellation state, test actual archive/text content.
- Selection probe races: preserve existing policy/profile/network/runtime revision checks; never let stale automatic results override newer manual selection.
- Screenshot contracts: generate disposable actual/diff evidence and review intended changes before isolated narrow recording by golden-blesser. The user authorized affected PNG updates and, on 2026-10-05, separately approved the three reviewed archive JSON fixtures and the one-symbol TUN JNI baseline diff. Other baseline changes require their applicable authorization workflow.
- Real native UI/lifecycle acceptance uses the owned API 37 ARM64 16 KiB emulator. This is emulator evidence; physical-device acceptance remains unobserved.

## Migration Plan

- Add app-private local metadata/pause state with absent-record defaults and deletion/reset cleanup. Keep deny-all backup exclusions intact. Do not store raw network identities in selection metadata.
- Deliver seven independently reviewed feature commits in order. Before each integration fetch/rebase origin/main, rerun relevant gates, architecture health and locked Cargo metadata; fast-forward main and push as authorized.
- Narrow gates: app targeted `testGithubFullDebugUnitTest`, affected core unit tests, theme tests, `:app:lintGithubFullDebug :core:service:lintDebug`.
- Combined runtime gate: `staticAnalysis`, affected service/runtime-state/selector suites and lifecycle smoke when supported. Heavy commands run through build-gate with at most four Gradle/Cargo jobs.
- Verify exact remote HEAD and terminal hosted CI for each pushed feature, or record a concrete pre-existing/environment blocker without claiming acceptance.
- Rollback is a new reverting commit, not history rewriting. Persisted unused state remains inert; a pending pause must be explicitly invalidated during rollback.
