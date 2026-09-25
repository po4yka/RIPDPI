# RLY-1790339260163788: Expose dedicated relay profile editors from VPN settings

## Objective

Reach all four existing dedicated profile creation screens from VPN configuration.

## Ownership

Relay UI agent owns `ConfigScreen.kt`, `VpnConfigScreen.kt`, `RipDpiNavHost.kt`, `RipDpiTestTags.kt`, focused tests, and this change. No shared locale writes.

## Execution

## Verification

Run focused `ConfigScreenTest` and `RipDpiNavHostLogicTest` with `-Pripdpi.skipNativeBuild=true`, then `:app:testGithubFullDebugUnitTest` and `:app:lintGithubFullDebug`.
- [x] RLY-1790339527554682 Expose dedicated editor destinations from VPN Add profile menu and verify navigation #bug !high @item:RLY-1790339260163788
