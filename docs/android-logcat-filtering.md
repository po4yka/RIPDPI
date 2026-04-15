# Android logcat filtering

## FrameEvents noise

When running RIPDPI on an emulator (and, less frequently, on real
hardware), logcat regularly emits blocks like:

```
E/FrameEvents: addLatch: Did not find frame.
E/FrameEvents: addRelease: Did not find frame.
E/FrameEvents: addPreComposition: Did not find frame.
E/FrameEvents: addPostComposition: Did not find frame.
```

These are **not** RIPDPI errors. They come from Android's libgui
`FrameEventHistory` — a callback from SurfaceFlinger couldn't be matched
to a queued frame record. Typical triggers:

- Emulator GPU/VSYNC drivers with loose frame-event tracking.
- `ViewRootImpl` restart after configuration changes.
- Intensive recomposition where the client-side queue rolls over
  before the SurfaceFlinger callback arrives.

There is no app-side remediation. Filter them from logcat when
debugging RIPDPI:

```bash
# Drop the whole tag at emulator/device level:
adb logcat -s 'ripdpi-native:*' 'com.poyka.ripdpi:*' FrameEvents:S

# Or post-filter:
adb logcat | grep -v FrameEvents
```

## ripdpi-native tags worth keeping

- `ripdpi-native:*` — everything the Rust bridge emits (routed via the
  `tracing` facade).
- `com.poyka.ripdpi:*` — Kotlin-side logs.

## Known benign stderr sources

- `InputDispatcher ... Channel is unrecoverably broken` after the
  activity finishes — harmless teardown.
- `SurfaceFlinger ... writeReleaseFence failed. error 104` — same class
  of AOSP bookkeeping noise, ignore unless it precedes a real crash.
