## Context

The Android service has a shared guarded engine-failure path, bounded handover
retries and a TUN replacement path that keeps the old interface until establish.

## Goals / Non-Goals

- Honor the blocking setting across crash paths.
- Restore forwarding on usable network events after handover exhaustion.

## Decisions

- Reuse the guarded engine-failure path for Xray and classify a spontaneous
  proxy exit with code zero as an engine failure.
- Scope handover recovery to the session with a retained barrier. Keep retries
  bounded per event. Restore Connected and telemetry on a successful restart.
- Serialize recovery, stop and replacement with the existing lifecycle mutex.
  If the network event is superseded or startup fails, quiesce any forwarding
  that the attempt started and keep the installed TUN.
- Keep the handover continuation claim while its TUN is retained. A retry can
  replace only the exact claim for the same command and positive predecessor.
  A new stop or activation still supersedes that authority.

## Contracts and ownership

- The Android service and runtime-state authority change. The persisted
  command format, user settings, JNI and Rust crates stay unchanged.

## Risks / Trade-offs

- The old TUN remains installed during retry. Android controls route convergence
  when establishment replaces it. Check this behavior on a physical device.

## Migration Plan

No migration. Revert the two fixes to roll back. Verify service unit tests for
both editions, static analysis and the repository source gates before push.
