# Android logcat filtering

## FrameEvents noise

When running RIPDPI on an emulator (and, less frequently, on real hardware), logcat regularly emits blocks like:

```
E/FrameEvents: addLatch: Did not find frame.
E/FrameEvents: addRelease: Did not find frame.
E/FrameEvents: addPreComposition: Did not find frame.
E/FrameEvents: addPostComposition: Did not find frame.
```

These are **not** RIPDPI errors. They come from Android's libgui `FrameEventHistory` — a callback from SurfaceFlinger couldn't be matched to a queued frame record. Typical triggers:

- Emulator GPU/VSYNC drivers with loose frame-event tracking.
- `ViewRootImpl` restart after configuration changes.
- Intensive recomposition where the client-side queue rolls over before the SurfaceFlinger callback arrives.

There is no app-side remediation. To collect logs, get the app UID from the device. Set `APP_UID` to the number after `uid:`. The UID filter keeps Kotlin, native and crash records across process restarts.

```bash
adb shell pm list packages -U com.poyka.ripdpi
adb logcat --uid="$APP_UID" -b main -b system -b crash -v threadtime FrameEvents:S
```

The default Kotlin tag is `ripdpi`. Components can use their own tags. Use the UID filter to keep those records.

For native-only debugging, select the exact tags:

```bash
adb logcat --uid="$APP_UID" -v threadtime -s ripdpi-native ripdpi-tunnel-native \
  ripdpi-relay-native ripdpi-warp-native ripdpi-amneziawg-native
```

## Known benign stderr sources

- `InputDispatcher ... Channel is unrecoverably broken` after the activity finishes — harmless teardown.
- `SurfaceFlinger ... writeReleaseFence failed. error 104` — same class of AOSP bookkeeping noise, ignore unless it precedes a real crash.
