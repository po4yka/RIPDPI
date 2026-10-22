# VPN-1792657107995294

## Execution

- [x] VPN-1792657277467804 Remove subscription credentials from SHARE exports #bug !high @item:VPN-1792657107995294
- [x] VPN-1792657278981644 Resolve native relay hostnames on the VPN underlay #bug !high @item:VPN-1792657107995294
- [x] VPN-1792657282574861 Stop surviving runtimes after unexpected exit #bug !high @item:VPN-1792657107995294
- [x] VPN-1792657284863659 Schedule new long-lived subscription refresh #bug !high @item:VPN-1792657107995294
- [x] VPN-1792657285994594 Preserve supported candidates in deployment bundles #bug !high @item:VPN-1792657107995294
- [x] VPN-1792657287269408 Verify combined changes and backend compatibility #bug !high @item:VPN-1792657107995294

## Ownership

Ownership is assigned in docs/tasks/issues/repair-vpn-rollout-audit.md. Each writer uses a separate worktree. The integrator owns tasking and shared validation.

## Verification

Run targeted Kotlin/Rust regressions, affected lint, strict task validation, architecture health, locked Cargo metadata, Android debug build and backend contract checks. Record device and remote CI results separately.
