---
id: AND-1794054244138121
title: Restore Hiddify imports with raw IPv6 targets
kind: bug
status: review
area: android
priority: medium
owner: Import parser
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-07
updated: 2026-11-07
spec_reason: regression-tested-single-module
status_detail: Hiddify fix and own parser tests passed; full app gate has one separate existing v2rayng/Trojan scanner failure, recorded with baseline evidence.
---

## Goal

Restore raw Hiddify subscription URLs with bracketed IPv6 hosts without changing existing import validation. This fixes the P2 review finding after AND-1794036523065646 at 080b8d59f.

## Acceptance criteria

- Raw and encoded Hiddify IPv6 targets return the same URL, display name, and bootstrap intent as other HTTPS targets.
- Preserve target query escapes and literal plus, first-fragment display names, and single decoding of encoded nested URLs.
- Invalid HTTPS targets and malformed percent escapes remain rejected; other client schemes keep their behavior.
- Observe the regression fail before the fix, then pass the app unit suite, staticAnalysis, ci-gates, and independent review.

## Ownership and approach

One writer owns app/src/main/kotlin/com/poyka/ripdpi/proxyimport/SingBoxDeepLinkParser.kt and its existing unit tests. Separate the exact Hiddify import prefix and outer remark before URI validation. Validate the nested URL in its own URI context, then use the existing HttpUrl HTTPS check. No dependency, schema, resource, or network execution change.

The user directed work and commits directly on main. This overrides worktree isolation for this fix. Do not push or publish.

## Verification

- Red reproduction: :app:testGithubFullDebugUnitTest filtered to SingBoxDeepLinkParserTest.
- Final app unit gate: ./gradlew :app:testGithubFullDebugUnitTest.
- ./gradlew staticAnalysis after preparing both app KSP variants.
- just ci-gates and ./taskctl validate.
- Independent pr-reviewer pass before committing the implementation.

## Evidence

- Red regression: 25 SingBox parser tests ran; only the new IPv6 case failed before the implementation.
- Final full app gate: 3430 tests, 3429 passed, one failure, no skipped tests. All 25 SingBox parser tests passed, including raw/encoded IPv6, query escapes, bootstrap, display names, and rejected malformed inputs.
- The failure is ScannerUriDispatcherTest: a share link inside a v2rayng install link imports as that profile. Its Trojan nested target is rejected by the existing HTTPS guard. Actual parsers from committed 080b8d59f and the current tree both return BadEncoding for the exact fixture. Scanner code and that test are unchanged. This is a separate regression of the original migration, not a failure introduced by this Hiddify fix.
- staticAnalysis passed after both app KSP variants were prepared. just ci-gates passed; its Darwin recipe skips the Linux-owned runtime-platform API snapshot.
- Independent pr-reviewer passed the corrected diff and compared actual base/current parsers on boundary inputs. A test-only pair/destructuring error was corrected before final validation.
- Logs: /tmp/ripdpi-hiddify-red.log, /tmp/ripdpi-hiddify-app-tests-final.log, /tmp/ripdpi-hiddify-static.log, /tmp/ripdpi-hiddify-ci.log. Baseline comparison: /tmp/ripdpi-hiddify-base-probe.json.
- No dependencies, golden files, or baselines changed. No device or remote CI acceptance is claimed.

The fix is ready for code review. Full-suite acceptance remains pending the separate v2rayng/Trojan scanner regression; do not treat the app gate as passed.
