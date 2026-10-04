## Context

The approved research defines seven sequential feature slices. Existing RDS controls, diagnostic path enums, telemetry, profile stores, export redactors and protected payload URL-tests provide the base. Discovery found missing scan explanations, technical path enum display, unused health timestamps, saved-setting facets presented as active, absent picker search, direct export launch, and no durable timed pause.

## Goals / Non-Goals

- Goal: deliver all seven behaviors with factual source-derived UI and explicit failure states.
- Goal: retain Android offline/non-root operation and user-controlled exports.
- Non-goal: geography maps, synthetic scores, fabricated router measurements, commercial subscriptions or a new backend.

## Decisions

- Preserve RAW_PATH/IN_PATH as machine data; localize presentation and explain the actual direct-path interruption without unconditional restoration promises.
- Publish measurement timestamps/windows from the owning service DTO. Do not reuse the five-minute connection-health window for cumulative DNS/latency aggregates.
- Capture an immutable applied configuration summary in service ownership when the runtime starts; compare saved settings to the applied summary. Do not infer applied config from a UI observation of Connected.
- Reuse diagnostic family grouping and existing relay types. Search is local/saveable; filtering or closing never changes selection.
- Prepare actual export content for preview before external launch. Preserve the existing fixed redacted/unlinkable archive policy; never introduce an unredacted archive option merely to match a checkbox.
- Implement durable pause using persisted generation/mode/deadline and service-owned scheduling/reconstruction. Retain the existing specialUse foreground service with a paused notification while releasing native runtime/TUN, and use permissionless setAndAllowWhileIdle alarm delivery plus sticky reconstruction. State that Android may delay idle delivery; never promise exact wakeup. Confirm shell teardown/boot ownership before slice six edits. An activity coroutine or unbounded delayed worker is insufficient. Android references: https://developer.android.com/reference/android/app/Service#START_STICKY and https://developer.android.com/reference/android/app/AlarmManager#setAndAllowWhileIdle(int,long,android.app.PendingIntent).
- Pause resumes the chosen mode using current saved settings, with this effect stated in the UI. Explicit Start/Stop supersedes it; configuration/profile edits invalidate pending intent. A stale deadline callback cannot restart a newer user intent. Boot handling must honor OS eligibility and show deferred/unavailable recovery honestly.
- Favoriting/recent metadata is separate from secret-bearing profile configurations. Record recently selected profiles with that exact label unless confirmed successful activation evidence is available. Prune references on deletion/reset.
- Expose the existing measured URL-test selector capability, including visible/configurable probe URL, actual member latency and observation time; label it lowest measured HTTP response time. Retain existing scope/revision fencing and protected candidate cleanup. Unsupported/all-failed results preserve the current valid selection.
- Remove synthetic active-profile/failover assertions in the touched selector UI: require exact profile ID match and actual event evidence.

## Contracts and ownership

- `:app`: UI, ViewModels, profile metadata/selector presentation, resources in all ten locales.
- `:core:diagnostics`: presentation/export payload preparation and existing redaction policies.
- `:core:service`: applied summary, health DTO metadata, durable pause orchestration and candidate probes.
- `:core:data:runtime-state`: persisted pause intent and boot/process recovery; other data modules only if their owning repositories require additive fields.
- No Rust crate or JNI/wire changes planned. Serialize any needed protobuf/storage schema changes through the one active feature writer and update every consumer in that slice.
- Root exclusively owns OpenSpec/task state and integration. Each slice has one implementation writer in a dedicated worktree; review agents never modify files. Locale resources and persistence schemas are serialized.

## Risks / Trade-offs

- Android foreground-service, VPN consent, sticky restart and boot restrictions: use existing ownership/notification contracts, verify denied-start and reconstruction paths, and obtain emulator/device evidence when available.
- Export preview races and secrets: preview exact prepared content; fence session changes, clear cancellation state, test actual archive/text content.
- Selection probe races: preserve existing policy/profile/network/runtime revision checks; never let stale automatic results override newer manual selection.
- Screenshot contracts: generate disposable actual/diff evidence, review intended changes, then obtain explicit fixture-family blessing authorization before recording any baseline.
- No attached Android device was found at discovery. Local tests and artifacts do not substitute for observed physical-device acceptance.

## Migration Plan

- Add app-private local metadata/pause state with absent-record defaults and deletion/reset cleanup. Keep deny-all backup exclusions intact. Do not store raw network identities in selection metadata.
- Deliver seven independently reviewed feature commits in order. Before each integration fetch/rebase origin/main, rerun relevant gates, architecture health and locked Cargo metadata; fast-forward main and push as authorized.
- Narrow gates: app targeted `testGithubFullDebugUnitTest`, affected core unit tests, theme tests, `:app:lintGithubFullDebug :core:service:lintDebug`.
- Combined runtime gate: `staticAnalysis`, affected service/runtime-state/selector suites and lifecycle smoke when supported. Heavy commands run through build-gate with at most four Gradle/Cargo jobs.
- Verify exact remote HEAD and terminal hosted CI for each pushed feature, or record a concrete pre-existing/environment blocker without claiming acceptance.
- Rollback is a new reverting commit, not history rewriting. Persisted unused state remains inert; a pending pause must be explicitly invalidated during rollback.
