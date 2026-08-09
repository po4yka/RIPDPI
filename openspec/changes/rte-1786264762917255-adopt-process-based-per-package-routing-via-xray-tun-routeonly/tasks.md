# RTE-1786264762917255: Verify app exclusions across app-managed and Android 17 paths

## Objective

Prove the landed app-managed policy and Android 17 OS delegation preserve exclusions and produce the intended two egress paths.

## Ownership

- Android VPN exclusion policy, Android 17 delegation, and reconnect lifecycle
- physical-device two-egress evidence lane

## Execution

- [x] RTE-1786264762918248 Enforce app exclusions through VpnService Builder policy #feature @item:RTE-1786264762917255
- [x] RTE-1786264762918389 Expose per-app allow and exclude controls #feature @item:RTE-1786264762917255
- [x] RTE-1786264762918168 Seed known platform-sensitive app policy #feature @item:RTE-1786264762917255
- [ ] RTE-1786266573979890 Verify on Android 17 that excluded apps use direct egress, allowed apps use the configured tunnel, and exclusions persist across reconnect #feature @item:RTE-1786264762917255

## Verification

- `./gradlew :core:service:testDebugUnitTest`
- physical Android 17 two-egress and reconnect journey with exact artifact/SHA evidence
