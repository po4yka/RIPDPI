---
title: Add NativeSignsChecker via JNI for Interface and Hook Detection
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: [add-ip-consensus-synthesis]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add NativeSignsChecker via JNI for Interface and Hook Detection #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `NativeSignsChecker` that uses JNI to call native C code for: `getifaddrs()` network interface enumeration, `/proc/self/maps` hook library detection, native routing table read, and root artifact detection.

## Context

Android's Java `NetworkInterface` APIs can be hooked by VPN implementations to hide tunnel interfaces. Reading the same data natively via `getifaddrs()` and comparing against the JVM view reveals hidden interfaces. `/proc/self/maps` scanning detects instrumentation frameworks (Frida, Xposed, Zygisk) that could be masking VPN activity. Root detection (Magisk, KernelSU, APatch) is a strong correlated signal for sophisticated bypass setups.

RKNHardering's native layer uses CMake + NDK. RIPDPI already has a Rust/NDK build in `native/rust/` — add a small C companion in the same native module or a new `native-detection` CMake target.

**Reference implementation (Java side):** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/NativeSignsChecker.kt`
**Reference CMakeLists.txt:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/cpp/CMakeLists.txt`
**Reference native sources:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/cpp/` — interface probe, routing table, maps scanner, root detection

**RIPDPI native build:** `native/rust/` + existing NDK config in `app/build.gradle.kts`

**RIPDPI Kotlin extension points:**
- New `NativeSignsChecker.kt` in `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/`
- Add `NativeSignsResult` to `DetectionModels.kt`
- Wire port adapter as usual

## Acceptance criteria

- [ ] JNI layer compiles for arm64-v8a, armeabi-v7a, x86_64 ABIs
- [ ] `getifaddrs()` result cross-checked against `NetworkInterface.getNetworkInterfaces()`; discrepancies (hidden interfaces) produce `EvidenceConfidence.HIGH`
- [ ] `/proc/self/maps` scanned for known hook library names: `frida`, `substrate`, `xposed`, `lspatch`, `zygisk`, `riru`; any match is `EvidenceConfidence.HIGH`
- [ ] `rwx` large memory-mapped regions (>4MB) flagged as informational
- [ ] Root detection checks: `su` binary in PATH, Magisk/KernelSU/APatch artifacts in `/data/adb/`, `overlay`/`bind` mounts in `/proc/mounts`, SELinux permissive mode; each finding tagged with confidence
- [ ] Native library loading failure degrades checker to informational (no crash)
- [ ] Unit tests for the Kotlin layer with a mock JNI bridge

## TDD workflow

1. **Write tests first** — stub a `NativeSignsBridge` interface (wraps JNI calls) and create tests against the Kotlin layer using a fake bridge:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/NativeSignsCheckerTest.kt`:
     - `hidden_interface_produces_high_confidence_finding()` — fake bridge returns native interface `tun0` absent from JVM `NetworkInterface` list; assert `EvidenceConfidence.HIGH` hidden-interface finding; fails until checker exists
     - `frida_in_maps_produces_high_confidence_finding()` — fake bridge returns `/proc/self/maps` line containing `frida-agent`; assert hook-detection finding
     - `root_su_binary_produces_finding()` — fake bridge returns `su` binary present; assert root finding
     - `library_load_failure_does_not_crash()` — fake bridge throws `UnsatisfiedLinkError`; assert result is informational with `hasError=false`
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 fail (interface not found)
3. **Implement** — `NativeSignsBridge` interface + `JniNativeSignsBridge` real impl + JNI C code + `NativeSignsChecker`
4. **Confirm green** — `./gradlew :core:detection:test` (Kotlin layer); confirm NDK compilation via `./gradlew :app:assembleDebug`
5. **Refactor** — extract maps marker constants; clean up JNI error handling

## Definition of done

Native code compiles in CI. Unit tests green. NativeSigns card visible in `DetectionCheckScreen`. Hook detection finding produces `DETECTED` or `NEEDS_REVIEW` verdict escalation.
