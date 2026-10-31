## Context

Task ID: `UIX-1793256870879296`. Part of epic `EPC-1793256695505524`; blocked by `UIX-1793256778917552`. Owner decisions of 2026-10-29 apply.

## Goals / Non-Goals

- Goal: Give the app one adaptive Material 3 shell: navigation that changes form with the window size class, Material 3 top app bars, and predictive back animations.
- Non-goal: network, service, native, JNI, or storage behavior changes.

## Decisions

- Keep Navigation Compose and RipDpiNavHost; Navigation 3 is out of scope.
- Map the Material window size class to RipDpiWidthClass so existing layout tokens keep one source.
- Keep the existing label-fit fallback for bottom navigation at extreme font scale if NavigationSuiteScaffold cannot fit labels.

## Contracts and ownership

- Automation resource IDs and test tags stay stable unless a step names the change.
- Screenshot fixtures change only after owner approval.

## Risks / Trade-offs

- NavigationSuiteScaffold label layout at 200% font may differ from the current measured fallback; test with Russian and Arabic.
- Predictive back transitions can conflict with existing route transitions; keep reduced-motion behavior.
