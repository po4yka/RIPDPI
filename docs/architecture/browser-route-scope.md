# Owned-Stack Browser Route — Scope

**Status:** Intentional remediation-only entry point. **Not** main navigation.

## What it is

`OwnedStackBrowserScreen` (`app/.../ui/screens/browser/`) is a single-target browser
backed by the native owned-TLS HTTP client (`NativeOwnedTlsHttpFetcher` via JNI).
It is registered in `Route.all` but deliberately excluded from `Route.topLevel`
(which drives the bottom navigation bar: Home, Config, Diagnostics, Settings).
This constraint is enforced by a navigation unit test:

```
assertFalse(Route.topLevel.contains(Route.OwnedStackBrowser()))
```

## What it is *not*

- Not a general-purpose browser
- Not surfaced via BottomNav or any top-level Settings entry
- Not a replacement for the system WebView for in-app webviews
- Not intended for casual browsing — owned-stack TLS is heavier than the system one

## Why it exists

When the runtime's remediation logic determines that a target hostname requires
a fronted-TLS bypass (e.g. ECH-supporting CDN with a custom SNI) that the
direct VPN path and SOCKS relay cannot deliver, it surfaces an "Open in RIPDPI
browser" action in `DiagnosticsScanSection`. That action navigates to this screen
with the target URL pre-filled via `Route.OwnedStackBrowser(initialUrl = url)`.

## Entry path (as of 2026-04-27)

```
DiagnosticsScanSection
  └─ remediationAction.targetUrl
       └─ onOpenOwnedStackBrowser(url)          // lambda passed down from RipDpiNavHost
            └─ navController.navigate(Route.OwnedStackBrowser(url))
                 └─ OwnedStackBrowserRoute(initialUrl)
```

There is no feature flag gating this route; it is always compiled in but only
reachable when the diagnostics engine emits a remediation action with a target URL.

## When to revisit

Promotion to main navigation should be considered only if **all** of the following hold:

1. The owned-stack browser gains tabs, history, or bookmarks (full-feature scope)
2. There is a clear user story for casual browsing through the owned stack
3. The performance and APK-size cost of always-loaded owned-stack browser is
   justified by measurable user benefit

Until then: keep entry through remediation only.

## Related

- ROADMAP-CLEANUP.md task P1.5.1 — decision recorded 2026-04-27
- `app/.../ui/screens/browser/OwnedStackBrowserScreen.kt` — KDoc on `OwnedStackBrowserRoute`
- `app/.../ui/navigation/Route.kt` — `Route.topLevel` excludes `OwnedStackBrowser`
- `app/.../navigation/RipDpiNavHostLogicTest.kt` — enforces the exclusion via assertion
