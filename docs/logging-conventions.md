# Logging Conventions

This project uses [Kermit 2.x](https://kermit.touchlab.co/) for logging. All modules share a single global `Logger` instance configured in `RipDpiApp.onCreate()`.

## Severity Guide

| Level | When to use | Appears in release |
|-------|------------|-------------------|
| **ERROR** | Feature degraded or user-visible failure. Proxy startup failure, crash report corruption. | Yes |
| **WARN** | Unexpected but handled. Fallback resolver used, notification channel creation failed. | Yes |
| **INFO** | Operational milestones. Service started/stopped, diagnostics scan completed. | No |
| **DEBUG** | Developer-interest details. Native handle creation, lifecycle transitions, status changes. | No |
| **VERBOSE** | Deep tracing. DNS resolution details, per-packet logging. | No |

## Release vs Debug Behavior

- **Debug**: All levels (VERBOSE+) go to logcat via `platformLogWriter()`.
- **Release**: WARN+ goes to logcat AND `app_log.txt` via `FileLogWriter`. DEBUG/INFO/VERBOSE are suppressed.

The `FileLogWriter` writes to `filesDir/logs/app_log.txt` with 512KB rotation. Both current and previous log files are included in diagnostics archives as `app-log.txt`.

## Crash Breadcrumbs

All log entries (any severity) are captured by `BreadcrumbLogWriter` into an in-memory ring buffer of 50 entries. On crash, these breadcrumbs are serialized into the crash report JSON, providing pre-crash context.

## Subsystem Tags

Use `LogTags` constants from `core:data` when explicit tags add clarity:

```kotlin
import com.poyka.ripdpi.data.LogTags

Logger.withTag(LogTags.SERVICE).i { "Starting VPN" }
Logger.withTag(LogTags.DIAGNOSTICS).w(error) { "Scan failed" }
```

Available tags: `SERVICE`, `ENGINE`, `DIAGNOSTICS`, `DATA`, `UI`.

For most logging, Kermit's auto-tag (class name) is sufficient. Use explicit tags when the class name doesn't clearly identify the subsystem.

## Silent Exception Rule

Every `runCatching` or `try-catch` that discards an error **must** log at WARN minimum:

```kotlin
// Bad -- silently swallows error
runCatching { json.decodeFromString<Foo>(raw) }.getOrNull()

// Good -- logs before discarding
runCatching { json.decodeFromString<Foo>(raw) }
    .onFailure { Logger.w(it) { "Failed to decode Foo" } }
    .getOrNull()
```

Exception: reflection-based API probes that are expected to fail on older Android versions (e.g., `NetworkMetadataProvider`).

## Performance

Kermit uses inline lambdas. The message lambda is **never evaluated** if the severity is below the minimum threshold. No `if (isEnabled)` guards are needed:

```kotlin
// The string interpolation only runs if DEBUG is enabled
Logger.d { "Proxy readiness: state=${telemetry.state} elapsed=${elapsed}ms" }
```

## Diagnostics Archive Integration

The diagnostics archive ZIP includes:
- `logcat.txt` -- system logcat snapshot (512KB, filtered to app PID)
- `app-log.txt` -- persistent file log (WARN+ entries from `FileLogWriter`)
- `native-events.csv` -- structured native runtime events from Rust ring buffers
