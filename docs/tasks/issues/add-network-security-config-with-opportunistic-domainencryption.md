---
id: DGN-1786264762917626
title: Add network-security-config with opportunistic domainEncryption
kind: feature
status: blocked
area: diagnostics
priority: medium
risk: high
owner: Android 17 device evidence maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1786264762917626-add-network-security-config-with-opportunistic-domainencryption
created: 2026-04-20
updated: 2026-08-09
status_detail: externally-gated — Android 17 physical-device ECH attempt verification remains unavailable
---

## Summary

Add `res/xml/network_security_config.xml` with `<domainEncryption mode="opportunistic"/>` as the base config, and point `AndroidManifest.xml` at it. Opportunistic unlocks platform ECH when both the library and DNS say yes.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 §4A.

## Current status

Verified 2026-05-28 against the current tree:

- The manifest was already wired to `@xml/network_security_config`, and this pass adds `xml-v37` overlays so Android 17+ gets opportunistic `domainEncryption` without changing older-platform resources.
- The same pass adds enabled per-domain config blocks for the current owned-stack probe hosts used by the first browser/remediation slice.
- `NscDomainEncryptionGeneratorTest` covers generated enabled, disabled, and opportunistic `domainEncryption` blocks.
- `EchReadinessProbeInstrumentedTest` covers native HTTPS-RR plus rustls ECH negotiation when network tests are enabled; it does not prove platform Network Security Config ECH behavior.
- Still open: Android 17 instrumented proof that the platform stack attempts ECH from Network Security Config when DNS supplies a config.

## Acceptance criteria

- [x] Config file exists with the base `domainEncryption` block on the Android-17+ resource path.
- [x] Manifest references the config via `android:networkSecurityConfig="@xml/network_security_config"`.
- [x] App still builds on minSdk targets below Android 17; the new attribute is ignored harmlessly on older versions.
- [ ] Instrumented test on Android 17 confirms ECH is attempted when DNS surfaces an ECH config.

## Work log

- 2026-06-05: Audit confirmed — criteria 1–3 are [x] (source-verified: `app/src/main/res/xml/network_security_config.xml` base exists, `app/src/main/res/xml-v37/network_security_config.xml` has `<domainEncryption mode="opportunistic"/>`, manifest at line 33 references `@xml/network_security_config`, `minSdk=27` so xml-v37 overlay is harmlessly absent below API 37). Criterion 4 remains [ ]: `app/src/androidTest/kotlin/com/poyka/ripdpi/integration/NscPlatformEchInstrumentedTest.kt` exists but its own docstring explicitly states step 2 is "necessary-but-not-sufficient proof of an ECH *attempt*" — it only asserts HTTP 200-399, which succeeds whether or not ECH was attempted. Status remains `blocked` (Android API surfaces no ECH-attempt confirmation; physical Android 17 device + packet capture required).
- 2026-06-05: NOT done. Static parts confirmed in source (`res/xml/network_security_config.xml` base, `res/xml-v37/network_security_config.xml` with opportunistic `<domainEncryption>`, manifest `@xml/network_security_config`) — first three criteria met. A new committed instrumented test (`app/src/androidTest/kotlin/com/poyka/ripdpi/integration/NscPlatformEchInstrumentedTest.kt`, commit 246068e55) does NOT satisfy criterion 4: step 1 exercises the native rustls bridge (native readiness, not platform NSC), and step 2 only asserts the platform `HttpsURLConnection` *reaches* the host — under opportunistic mode the connection succeeds identically whether or not ECH was attempted, so it cannot confirm an ECH attempt. The test's own docstring calls step 2 "necessary-but-not-sufficient proof of an ECH attempt." Blocked: Android surfaces no API for ECH-attempt confirmation; closing this needs a physical Android 17 device plus a packet capture asserting an encrypted ClientHello inner SNI.
- **2026-05-28** — Docs audit refreshed the status. The static Network Security Config path and generated domain-encryption XML are covered by source/tests. The current instrumented ECH probe proves native ECH readiness, not platform Network Security Config ECH behavior, so the Android 17 platform proof remains open.
- **2026-05-16** — Blocked on Android 17 instrumented test requiring a physical device for ECH attempt verification. Static parts (config file `res/xml/network_security_config.xml` + `res/xml-v37/network_security_config.xml` overlay, manifest reference `@xml/network_security_config`, multi-platform build passing `just build`) are landed and verified. The remaining acceptance criterion (instrumented test on Android 17 confirming ECH is attempted when DNS surfaces an ECH config) cannot run in CI — no physical Android 17 device available.
- 2026-05-16: Reclassified to backlog — no concrete blocker recorded in frontmatter (physical device constraint is an environment limitation, not a tracked dependency slug).

## Links

- Epic - Owned-stack mode with Android 17 ECH
- ripdpi-android-direct-mode-plan-2026-04-20
