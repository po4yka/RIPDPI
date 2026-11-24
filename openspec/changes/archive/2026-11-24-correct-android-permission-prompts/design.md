# Design

Task ID: `AND-1795489914228572`

Use the existing VPN explanation with the pending action. Use the Android
activity result and permission rationale state for runtime recovery. Keep
notifications optional during validation. Plan detection requests per permission
and request both location grants together on Android 12 and higher. Use the
existing settings rows and banners for boot and update actions.

The Android maintainer owns app orchestration, VPN, scanner, and all locale
resources. The detection worker owns the detection permission planner, screen
and their tests. The settings worker owns app update and boot settings logic
and tests. Workers use separate worktrees. The maintainer serializes locale and
translation export changes and runs checks on the combined tree.
