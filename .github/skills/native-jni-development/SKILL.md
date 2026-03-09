---
name: native-jni-development
description: Use when modifying C code in core/engine/src/main/cpp/, adding JNI functions, changing CMakeLists.txt, or debugging native crashes and JNI linkage errors
---

# Native JNI Development

## Overview

RIPDPI has two native C libraries built separately. Modifications require understanding the build pipeline, JNI naming conventions, and upstream fork constraints.

## Native Libraries

| Library | Build | Source | Notes |
|---------|-------|--------|-------|
| `libripdpi.so` | CMake | `core/engine/src/main/cpp/` | byedpi fork + JNI bridge |
| `libhev-socks5-tunnel.so` | ndk-build | `core/engine/src/main/jni/hev-socks5-tunnel/` | Git submodule, do not modify |

## Build Pipeline

CMake builds byedpi sources into `libripdpi.so` via AGP's `externalNativeBuild`. The `runNdkBuild` Gradle task builds hev-socks5-tunnel before `preBuild` and outputs to `build/generated/jniLibs/`.

## JNI Bridge Pattern

The bridge lives in `core/engine/src/main/cpp/native-lib.c`. Kotlin wrapper: `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt`.

### Existing JNI Functions

```
Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocketWithCommandLine(JNIEnv*, jobject, jobjectArray args) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocket(JNIEnv*, jobject, ..28 params..) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniStartProxy(JNIEnv*, jobject, jint fd) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniStopProxy(JNIEnv*, jobject, jint fd) -> jint
```

### Adding a New JNI Function

1. Declare `external fun` in `RipDpiProxy.kt`
2. Implement in `native-lib.c` with exact JNI name: `Java_com_poyka_ripdpi_core_RipDpiProxy_<name>`
3. Use C99 standard, match existing style (params struct, error returns as -1)
4. Kotlin side: wrap with `Mutex` if it touches shared `fd` state

### Kotlin Wrapper Pattern

```kotlin
// RipDpiProxy.kt uses Mutex to serialize fd access
private val mutex = Mutex()
private var fd: Int = -1

suspend fun startProxy(preferences: RipDpiProxyPreferences): Int = mutex.withLock {
    fd = createSocket(preferences)
    withContext(Dispatchers.IO) { jniStartProxy(fd) }
}
```

## Rules

- **byedpi is an upstream fork** -- minimize modifications to `core/engine/src/main/cpp/byedpi/`. Prefer changes in `native-lib.c` or `utils.c`.
- **hev-socks5-tunnel is a git submodule** -- never modify files inside it. Update via `git submodule update`.
- **Never edit `.so` files** -- they are built from source.
- **NDK version, CMake version, ABI filters** are in `gradle.properties` (single source of truth). Do not hardcode in CMakeLists.txt or build.gradle.kts.
- **C99 standard** with `-O2 -D_XOPEN_SOURCE=500`. Compiler warnings are errors for implicit functions, type mismatches, and missing returns.
- **Page size flags**: `libripdpi.so` uses `-Wl,-z,max-page-size=16384` for 16KB page compatibility.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| JNI function name mismatch | Must match package: `Java_com_poyka_ripdpi_core_RipDpiProxy_<name>` |
| Missing `#include` for JNI types | Include `<jni.h>` and byedpi headers via `byedpi/` prefix |
| Modifying byedpi sources directly | Add wrapper logic in `native-lib.c` or `utils.c` instead |
| Forgetting Mutex on Kotlin side | All JNI calls touching fd must be wrapped in `mutex.withLock` |
| Adding ABI filters in module build file | Use `gradle.properties` -- convention plugin reads it |
