## Context

`ProfileImportConfirmViewModel` currently owns a parsed `ProxyProfile` in memory until the user presses Add. Add validates the model, creates a proxy group, persists relay profile and credential records through `RelayProfileActivator`, selects the relay, and emits the imported navigation event. There is no runtime check before those mutations.

The native relay runtime already supports an ephemeral local SOCKS port, bounded readiness, telemetry-based endpoint discovery, stop, and join behavior through `RipDpiRelayRuntime` and `UpstreamRelaySupervisor`. `RelayCapabilityProbe` already performs a cancellable, no-retry TCP request through a SOCKS endpoint. The new path must compose those capabilities without writing the imported profile into stores or borrowing the service-owned supervisor.

The VPN protect invariant prohibits starting an unprotected non-loopback relay socket while a VPN protection callback is active. A status-only check would have a start race, so admission must be coordinated with service startup rather than inferred only from the screen's last rendered status.

## Goals / Non-Goals

- Goal: Offer a truthful pre-import TCP path check that uses the exact runtime material projected from the parsed profile.
- Goal: Guarantee one attempt, a fixed deadline, session-local loopback state, and cleanup on every terminal path.
- Goal: Make service startup authoritative: it can preempt and drain a preflight before any VPN/proxy runtime opens sockets.
- Goal: Keep profile material in memory and redact all user-visible/logged failures to an allowlisted category.
- Non-goal: Validate UDP ASSOCIATE, VPN permission, TUN routing, DNS policy, failover, or every application protocol.
- Non-goal: Persist a verification receipt, change import eligibility, automatically import, or mark the profile globally validated.
- Non-goal: Change JNI, native wire, protobuf, Room, DataStore, or backward-compatibility contracts.

## Decisions

- Extract the pure profile-to-relay projection currently embedded in `RelayProfileActivator` into an app-owned `RelayProfileProjection` value containing one `RelayProfileRecord` and one `RelayCredentialRecord`. Both activation and preflight consume the same projection, preventing check/import drift without performing writes.
- Add a public `ImportedRelayProfilePreflight` interface in `:core:service` whose request contains the projected records and whose result is a closed, secret-free outcome enum. Keep raw exceptions and profile fields below this boundary.
- Refactor the existing runtime resolver so its pure record/credential resolution path can resolve a transient request directly. Store-backed service resolution remains a caller of the same function; the preflight never inserts a temporary store row.
- Add a singleton `RelayPreflightInterlock` in `:core:service`. A preflight registers its cancellable session under the interlock only after atomically confirming `ServiceStateStore.status == Halted`. Every VPN/proxy start path calls `cancelAndAwaitPreflight()` before registering the VPN protection callback or creating service runtimes. This gives service startup priority and closes the status-to-socket race. The interlock is process-local and carries no durable state.
- Create a dedicated runtime instance through `RipDpiRelayFactory`, override only `localSocksHost=127.0.0.1`, `localSocksPort=0`, and `udpEnabled=false`, start it in a child job, await readiness, resolve the effective loopback endpoint from telemetry, and call `RelayCapabilityProbe` with `EgressRequirements(tcpConnect=true, udpAssociate=false)`.
- Use a single total preflight deadline of 12 seconds and the repository's 5-second runtime cleanup deadline. The TCP probe retains its own shorter socket/call deadlines. There are no retries, alternate candidates, cooldown writes, or failover callbacks.
- Implement cleanup in `withContext(NonCancellable)` with `runtime.stop()`, bounded `job.join()`, then cancellation plus join as the fallback. The preflight result cannot become Success until cleanup completes successfully; a cleanup timeout is projected as failure.
- Model ViewModel state as a stable sealed state (`Idle`, `Checking`, `Succeeded`, and typed non-success categories) inside `ProfileImportConfirmUiState`. State is updated atomically. Add and Check are mutually disabled while either operation is running. A new profile clears the previous result.
- Render the check as a full-width secondary `RipDpiButton` before the existing Add button. Built-in button semantics supply the role and text label; the result card is text, uses localized neutral language, and is covered by Compose semantics assertions.
- Keep the existing Xray parser-only `Check profile` key and behavior unchanged. Add import-preflight-specific resource keys so the two actions can describe different guarantees. Add every new key to en, ru, es, de, fr, fa, ar, zh-CN, and hi.
- Keep the check advisory. A failed or unavailable preflight does not disable Add; Add independently executes the current validation/persistence/activation/rollback contract.

## Contracts and ownership

- `:app` owns `ProxyProfile` projection, `ProfileImportConfirmViewModel`, the Compose surface, Hilt wiring, localized strings, and UI/screenshot tests.
- `:core:service` owns `ImportedRelayProfilePreflight`, `RelayPreflightInterlock`, transient record resolution, runtime/probe orchestration, deadlines, secret-free failure mapping, and unit tests.
- `:core:engine-api` continues to own `RipDpiRelayRuntime`, `RipDpiRelayFactory`, and `ResolvedRipDpiRelayConfig`; no API change is planned unless a test seam is required without altering wire data.
- `:core:data:model` relay records are in-memory inputs to the service runner. Their persistence schemas and serializers are unchanged.
- No Rust crate, JNI method, protobuf, Room schema, DataStore schema, native schema version, `Cargo.lock`, or `gradle/libs.versions.toml` change is expected.
- Serialized shared-file lane: all nine `app/src/main/res/values*/strings*.xml` locale sets have one writer during apply. If the profile-import Roborazzi fixture changes, its screenshot family is a separate serialized lane and may be recorded only after explicit path/family authorization and semantic diff review.
- The existing main checkout is already one commit ahead of `origin/main`; the implementation branch is based on that exact `main` commit so integration will preserve the Navigation 3 change.

## Risks / Trade-offs

- Runtime resolver drift between activation and preflight could produce a false result -> extract one pure projection and one pure transient resolution function and contract-test equality with the store-backed path.
- A service start racing the check could open unprotected sockets or conflict with the native singleton -> service-priority interlock cancels and drains the preflight before service runtime/protect registration; race tests cover both orderings.
- Native start may suspend forever or ignore cancellation -> total and cleanup deadlines, explicit stop, cancel-and-join fallback, and a fake runtime test that never exits.
- A successful HTTP response may be overinterpreted -> the result says only that the test target was reached through the temporary relay and explicitly avoids VPN/validation language.
- A raw exception may leak endpoint or credentials -> map below the UI boundary to an allowlisted enum, never retain raw messages, and seed secret sentinels in privacy tests.
- A check can consume network/battery -> user initiated, one TCP request, no UDP, no retries, fixed deadline, and no background persistence.
- The profile-import screenshot changes intentionally -> run the existing screenshot test first; request family-specific blessing authorization if its checked-in expected image must be recorded, then inspect only that fixture diff.

## Migration Plan

1. Add the pure projection and transient resolver behind existing activation behavior; prove activation output is unchanged.
2. Add the service-priority interlock to all VPN/proxy start entry paths and prove service start drains a running preflight without changing normal startup.
3. Add the isolated service preflight runner and its deadline, non-mutation, privacy, race, and cleanup tests.
4. Add typed ViewModel state, the secondary Compose action, localized copy, semantics tests, and the intentional screenshot delta if separately authorized.
5. Run targeted JVM/Compose tests, locale lint, `staticAnalysis`, architecture health, and combined-tree checks after rebasing onto `origin/main`.
6. On a physical Pixel 7 with an owner-controlled relay profile, observe success and failure paths, verify one relay attempt, and confirm no listener/native handle remains and durable settings are unchanged.

Rollback removes the UI action, runner, interlock calls, and extracted projection while restoring the previous private activation mapping. No data rollback or migration is required because the feature creates no durable state and changes no serialized contract.
