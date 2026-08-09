# OUT-1786264762917107: Run Xray as managed VPN relay runtime

## Objective

Run Xray as managed VPN relay runtime

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762919162 Runtime registers libXray dialer/listener protection before starting Xray. — RipDpiXrayRuntime registers the protect controller with the bridge BEFORE start; protect-first ordering is asserted by RipDpiXrayRuntimeTest and XrayProtectFdCont… #feature !high @item:OUT-1786264762917107
- [x] OUT-1786264762919691 Startup waits for a concrete listener or verified Xray state before VPN tunnel handoff. — readiness success/timeout covered in RipDpiXrayRuntimeTest #feature !high @item:OUT-1786264762917107
- [x] OUT-1786264762919377 Stop path is bounded, idempotent, and reports typed clean/failed stop causes. — typed StopCause (Clean/AlreadyStopped/Failed), bounded via IO dispatcher; idempotent/late/hung-stop tests green #feature !high @item:OUT-1786264762917107
- [x] OUT-1786264762919314 Xray version and basic provider state flow into service telemetry without exposing profile secrets. — pollTelemetry() emits a NativeRuntimeSnapshot with version+state and a secret-free assertion test #feature !high @item:OUT-1786264762917107
- [x] OUT-1786264762919536 Unit or service tests cover startup failure, invalid config, late stop, and crash/exit mapping. — 14 tests in RipDpiXrayRuntimeTest (green offline in :core:engine-api) #feature !high @item:OUT-1786264762917107

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
