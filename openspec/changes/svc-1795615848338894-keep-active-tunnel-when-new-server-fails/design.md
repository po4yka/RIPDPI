## Context

Portfolio task `SVC-1795615848338894` owns this change. A failed attempt to connect to a new server does not leave the user without the connection they had, and the error tells them what to check.

## Goals / Non-Goals

- Goal: deliver `Keep the working tunnel when a new server fails to start` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Restore, not a prompt. Every explicit switch path already reverts the stored selection when the new server fails: `DefaultStandaloneAmneziaWgActivator` compensates the AWG pointer on a rollback-safe failure, and `RelayActivationSelectorReloadTrigger` and `MeasuredProfileActivationCoordinator` call `revertRejectedSwitch`. The failure path in the coordinators (`restartAfterTransportFailover`, `reloadConnectionPolicy`) still stops the whole runtime, so the device was left unprotected. After a successful revert, the new `FailedSwitchRestorer` in `core/service` starts the previous mode again through the ordinary `prepareStart` and `startPrepared` path. That path resolves the reverted selection, so it brings back the configuration that worked.
- Rejected alternative: re-apply the previous stack inside the coordinator. The session does not keep the previous `ConnectionPolicyResolution`, and the selection revert lives in the callers, so an in-place rollback would duplicate the revert logic and the composition state machine.
- Guards. The restorer captures the running mode before the switch and does nothing when nothing ran (a failed cold start stays failed), when the runtime still runs (a rejected reload keeps the old stack), or when the explicit user intent generation changed (a Start or Stop that the user issued during the switch wins). The AWG path compares against the generation that its own dispatch produced. The restart runs inside `runIfExplicitUserIntentCurrent`, and a Stop that reserves after the restart's reservation supersedes the receipt, so `startPrepared` rejects it.
- Scope. Only user-initiated switches restore: the AWG editor, a manual selector pick, and a measured profile selection. An automatic selector reload that fails keeps its current behavior. The relay-to-AWG automatic fallback belongs to SVC-1795015549149698.
- AWG readiness timeout. `awaitRuntimeReady` throws the typed `RuntimeReadinessTimeoutException` (still an `IllegalStateException`). `AmneziaWgRuntimeSupervisor` keeps the cohort retry budget and maps a timeout on the last attempt to `ServiceStartupRejectedException(FailureReason.AmneziaWgUnreachable)`, like the relay's `InitialTransportSelectionFailed`. It logs the raw `state=` detail at warning level for diagnostics. Home maps the reason to the localized `failure_awg_unreachable` string, which names the endpoint, the port, and the keys and tells the user to connect again. Telemetry records `amneziawg_unreachable` with failure class `Timeout`.
- Background notification. `ConnectionFailureAlert` (67edcaf389) already posts an alert for every `Failed` status while no activity is visible, and start failures report `Failed` through `ServiceStatusReporter.onFailed`. The audit ran with the app in the foreground, where Home shows the failure instead. No change was needed. A successful restore reaches `Connected`, and `ForegroundNotificationController` then cancels the alert.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.
- The restart follows a stop that asks Android to stop the service after its queued commands. The restart is queued after that stop, the same as a user who taps Connect right after a failure, but the gap is milliseconds instead of seconds. → A device run must confirm that the VPN comes back.
- A user Stop that reserves before the restart's reservation and dispatches after it loses to the restart. → The window is the duration of one `reserveStart` call; the user can stop again.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
