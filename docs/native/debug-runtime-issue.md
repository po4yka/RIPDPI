# Debug A Runtime Issue

This workflow is the shortest path for collecting one correlated debugging packet from a debug build:

1. Clear logcat so the capture only contains the reproduction window.
2. Reproduce the issue while the app is running.
3. Save the native/runtime logcat slice.
4. If the issue needs escalation, trigger the in-app support bundle and copy the latest archive from app cache.

The native logging path now emits one correlated event stream across:

- Android logcat
- live service telemetry (`ServiceStateStore.telemetry`)
- diagnostics history / archive export

The same runtime can now be joined through `runtimeId`, `mode`, `policySignature`, `fingerprintHash`, and, for diagnostics runs, `diagnosticsSessionId`.

## One-command capture

Use the helper wrapper from the repo root:

```bash
scripts/debug-runtime-issue.sh --pull-archive
```

What it does:

- runs `adb logcat -c`
- opens the logs screen through the existing automation start-route hook (`logs`) on debug builds
- waits for you to reproduce the issue
- writes `logcat-app.txt`, `logcat-native.txt`, and `capture-metadata.txt`
- optionally copies the newest support bundle from `cache/diagnostics-archives/`

Artifacts land under `build/runtime-debug/<timestamp>/`.

## Manual commands

Target a specific device:

```bash
export ANDROID_SERIAL=<serial>
```

Open the logs screen directly in a debug build:

```bash
adb shell am start -n com.poyka.ripdpi/.activities.MainActivity \
  --ez com.poyka.ripdpi.automation.ENABLED true \
  --es com.poyka.ripdpi.automation.START_ROUTE logs
```

Clear logcat and capture only the app process:

```bash
adb logcat -c
adb logcat -d -v threadtime --pid="$(adb shell pidof -s com.poyka.ripdpi)"
```

Filter native adapter tags:

```bash
adb logcat -d -v threadtime | grep -E 'ripdpi-native|ripdpi-tunnel-native'
```

Pull the newest support bundle manually from a debug build:

```bash
adb shell run-as com.poyka.ripdpi sh -c \
  'cd cache/diagnostics-archives && ls -1t ripdpi-diagnostics-*.zip | head -n1'

adb exec-out run-as com.poyka.ripdpi cat \
  cache/diagnostics-archives/<archive-name>.zip > /tmp/<archive-name>.zip
```

## What To Trigger In The App

For release-support quality captures:

1. Reproduce the issue.
2. Open the Logs screen.
3. Use `Share support bundle` first.
4. Use `Save logs` only when you specifically need raw logcat outside the archive.

The support bundle is the preferred artifact because it now includes:

- lifecycle milestones
- last failure summary
- active policy signature
- network scope
- recent native warnings and errors
- correlated `native-events.csv` rows

## Common Android Native Load-Time Failures

When `libripdpi.so` or `libripdpi-tunnel.so` fail to load, check these issues:

### Missing libc++_shared.so

Error: `libripdpi.so: cannot find -lc++_shared.so.1` or undefined symbol related to C++ stdlib

Solution: The native libraries use `c++_static` STL to avoid the additional runtime dependency. If you see this error:

1. Verify gradle.properties includes `ripdpi.android.cppStl=c++_static`
2. Rebuild with `./gradlew clean assembleDebug`
3. Check the build log for "linking libc++abi for __gxx_personality_v0 when using c++_static"

### Undefined Symbol at dlopen

Error: `dlopen failed: cannot locate symbol "some_function_name"` 

Diagnosis: Use `nm -D` to check what's actually exported:

```bash
adb pull /data/app/com.poyka.ripdpi-*/lib/arm64-v8a/libripdpi.so /tmp/
nm -D /tmp/libripdpi.so | grep your_symbol
```

If the symbol is missing, the crate defining it was not linked or was compiled with visibility `#[visibility="hidden"]`.

### JNI Symbol Mismatch with @JvmStatic Companion

Error: `java.lang.UnsatisfiedLinkError: No implementation found for...`

When using `@JvmStatic` on a companion object method in Kotlin, the exported JNI symbol is on the **class**, not the `$Companion` inner class. 

- Kotlin: `class MyClass { companion object { @JvmStatic fun foo() {} } }`
- JNI export: `Java_com_poyka_ripdpi_MyClass_foo` (not MyClass_$Companion_foo)
- Verify with `nm -D libripdpi.so | grep Java_...`

## Build Notes

- Debug builds install verbose app logging through Kermit's `platformLogWriter()`.
- Release behavior stays on-demand; there is no always-on file logger in this phase.
- The automation launch extras only work in debug builds, because `DebugAutomationController` is bound from `app/src/debug/`.
