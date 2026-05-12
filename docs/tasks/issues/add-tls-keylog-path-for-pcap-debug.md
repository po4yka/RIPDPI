---
title: Add TLS Keylog Path Setting for Pcap Debugging
type: task
status: doing
area: diagnostics
priority: low
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-12
---

- [ ] #task Add TLS Keylog Path Setting for Pcap Debugging #repo/RIPDPI #area/diagnostics #status/doing 🔽

## Objective

Add a hidden setting `dpi.diagnostics.tlsKeylogPath: String?` that, when non-null, instructs `DiagnosticTlsClientFactory` to write each TLS connection's pre-master-secret in the SSLKEYLOGFILE format to that path — letting power users decrypt diagnostic-probe pcaps in Wireshark for deep debugging. Gated behind dev-mode and Privacy Mode.

## Context

dpi-ch's config exposes `key-log-path` as an "expert warn" option — the use case is when a probe verdict doesn't match expectations and the user wants to inspect the actual TLS bytes on the wire. With pcaps + the keylog file, Wireshark decrypts and renders the entire TLS handshake including the encrypted ClientHello extensions, ServerHello selections, alerts, etc. This is irreplaceable for diagnosing middlebox-induced TLS handshake aborts that don't match any documented pattern.

The feature is dangerous: writing pre-master secrets to disk creates an exfiltratable copy of every TLS session's plaintext. So:
- **Default:** disabled (`tlsKeylogPath = null`)
- **Setting hidden:** only visible in `add-detection-debug-mode` advanced section
- **Privacy Mode override:** when Privacy Mode ON, the setting is forced null regardless of stored value
- **File location restriction:** path must be under `Context.filesDir` or the user-selected SAF directory; arbitrary paths rejected
- **Auto-rotate:** after each diagnostic-suite run, the file is moved to `<path>.<timestamp>` and a fresh empty file is created — prevents cross-run secret contamination
- **Auto-purge after 24h:** rotated files older than 24h auto-deleted (configurable retention)

**SSLKEYLOGFILE format** (per Mozilla NSS docs):
```
<Label> <ClientRandom> <Secret>
```
Where Label = `CLIENT_RANDOM` (TLS 1.2) or `CLIENT_HANDSHAKE_TRAFFIC_SECRET` / `SERVER_HANDSHAKE_TRAFFIC_SECRET` / `CLIENT_TRAFFIC_SECRET_0` / `SERVER_TRAFFIC_SECRET_0` / `EARLY_EXPORTER_SECRET` (TLS 1.3).

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/docs/README.md` (`key-log-path`) + Mozilla NSS Key Log Format.

**RIPDPI placement:**
- Setting integration: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/TlsKeylogWriter.kt`
- Settings UI: extends `add-detection-debug-mode` with the keylog path entry

## Acceptance criteria

- [ ] Setting key `dpi.diagnostics.tlsKeylogPath: String?` in DataStore; default null
- [ ] Setting visible only when `add-detection-debug-mode` is ON; hidden otherwise
- [ ] Privacy Mode override: when Privacy Mode ON, `effectiveTlsKeylogPath()` returns null regardless of stored value
- [ ] Path validation: must start with `Context.filesDir` absolute path or a user-selected SAF directory; reject otherwise with toast "TLS keylog path must be inside app storage"
- [ ] `TlsKeylogWriter.append(label, clientRandom, secret)` — writes one line per call in SSLKEYLOGFILE format with newline; thread-safe (`Mutex` around `FileOutputStream` append)
- [ ] `DiagnosticTlsClientFactory` consults the setting at connect time; if non-null, attaches a keylog callback to the TLS engine; otherwise no callback (zero overhead)
- [ ] Auto-rotate at end of suite run: `renameTo("<path>.<timestamp>")`; new empty file created
- [ ] Auto-purge: rotated files older than `keylogRetentionHours` (default 24) deleted on next suite run
- [ ] When keylog active, diagnostic results UI shows a banner: "TLS keylog enabled — secrets written to <path>. Disable when done debugging."
- [ ] Native uTLS bridge from `add-utls-diagnostic-probe-clienthello-fingerprinting` exposes a `setKeylogCallback(cb)` API; JSSE fallback uses the standard `SSLContext` keylog hook (Conscrypt API ≥ 31)
- [ ] Unit tests: setting validation; rotate behavior; Privacy Mode override; file format

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpich/TlsKeylogWriterTest.kt`:
     - `append_writes_correct_format_line()` — `append("CLIENT_RANDOM", "abc123", "def456")`; assert file contains `"CLIENT_RANDOM abc123 def456\n"`; fails until writer exists
     - `concurrent_appends_serialised()` — 100 concurrent appends; assert 100 lines, no truncation
     - `rotate_renames_with_timestamp()` — call `rotate()`; assert `<path>.<unix_timestamp>` created, new empty `<path>` exists
     - `purge_deletes_files_older_than_retention()` — fake 3 rotated files dated -48h, -12h, -1h, retention 24h; call `purgeOld()`; assert -48h deleted, others retained
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpich/TlsKeylogSettingsTest.kt`:
     - `setting_disabled_by_default()` — fresh DataStore; assert `tlsKeylogPath == null`
     - `path_outside_filesdir_rejected()` — set `/etc/passwd`; assert validation throws with message
     - `privacy_mode_overrides_setting()` — set valid path AND Privacy Mode ON; assert `effectiveTlsKeylogPath() == null`
     - `keylog_callback_attached_when_path_set()` — fake setting non-null; instrument `DiagnosticTlsClientFactory`; assert callback registered with TLS engine
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `TlsKeylogWriter`, setting validation, factory integration, rotate/purge logic
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract retention policy into a `KeylogRetentionPolicy(hoursToKeep)` class for testability

## Definition of done

All 8 unit tests green. Setting visible in debug mode only. Banner in UI when active. Wireshark successfully decrypts a captured pcap using the emitted keylog file (manual verification step).

## Work log

### 2026-05-12 - Local writer/settings foundation

- Added `TlsKeylogWriter`, `TlsKeylogSettings`, `TlsKeylogPathValidator`, and `KeylogRetentionPolicy` under `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/`.
- Added focused JVM tests for SSLKEYLOGFILE line writing, concurrent append serialization, rotate/create-fresh behavior, rotated-file purge, path validation, debug visibility, and Privacy Mode override.
- Verification:
  - `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.dpich.TlsKeylogWriterTest --tests com.poyka.ripdpi.diagnostics.dpich.TlsKeylogSettingsTest -Pripdpi.skipNativeBuild=true`
  - `./gradlew :core:diagnostics:ktlintCheck -Pripdpi.skipNativeBuild=true`
  - `python scripts/ci/check_architecture_health.py --check --paths core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/TlsKeylogWriter.kt core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpich/TlsKeylogWriterTest.kt core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpich/TlsKeylogSettingsTest.kt`
  - `git diff --check`
- Remaining before close: DataStore field, debug-only settings UI, diagnostic-result warning banner, TLS engine callback wiring, suite-end rotate/purge integration, and manual Wireshark decrypt proof.

### 2026-05-12 - DataStore key foundation

- Added `detection_diagnostic_tls_keylog_path` to `AppSettings` field 284 with an empty-string disabled default.
- Added focused serializer tests for the default value and persisted round-trip.
- Verification:
  - `./gradlew :core:data:testDebugUnitTest --tests com.poyka.ripdpi.data.DpiSuiteSettingsTest -Pripdpi.skipNativeBuild=true`
  - `./gradlew :core:data:ktlintCheck -Pripdpi.skipNativeBuild=true`
  - `python scripts/ci/check_architecture_health.py --check --paths core/data/model/src/main/proto/app_settings.proto core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/AppSettingsSerializer.kt core/data/src/test/kotlin/com/poyka/ripdpi/data/DpiSuiteSettingsTest.kt`
  - `git diff --check`
- Remaining before close: debug-only settings UI, diagnostic-result warning banner, TLS engine callback wiring, suite-end rotate/purge integration, and manual Wireshark decrypt proof.

### 2026-05-12 - Debug settings UI foundation

- Mapped the stored TLS keylog path into `DetectionSettingsUiState` with debug-mode visibility and Privacy Mode suppression.
- Added a hidden `TLS keylog path` field under Detection settings > Diagnostic probes, disabled while Privacy Mode is on.
- Added `DetectionSettingsViewModel.setTlsKeylogPath()` for persisted UI updates.
- Verification:
  - `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.detection.DetectionSettingsUiModelTest -Pripdpi.skipNativeBuild=true`
  - `./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true`
  - `python scripts/ci/check_architecture_health.py --check --paths app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsModels.kt app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsScreen.kt app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsViewModel.kt app/src/test/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsUiModelTest.kt`
  - `git diff --check`
- Remaining before close: diagnostic-result warning banner, TLS engine callback wiring, suite-end rotate/purge integration, and manual Wireshark decrypt proof.

### 2026-05-12 - Detection result warning banner

- Added `DetectionCheckUiState.tlsKeylogWarningPath`, which is only effective when debug mode is enabled, Privacy Mode is off, and a non-blank path is stored.
- Added the detection-screen warning banner: "TLS keylog enabled" with the active path and disable reminder.
- Verification:
  - `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.detection.DetectionCheckUiStateTest -Pripdpi.skipNativeBuild=true`
  - `./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true`
  - `python scripts/ci/check_architecture_health.py --check --paths app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckViewModel.kt app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckScreen.kt app/src/test/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckUiStateTest.kt`
  - `git diff --check`
- Remaining before close: TLS engine callback wiring, suite-end rotate/purge integration, and manual Wireshark decrypt proof.

### 2026-05-12 - Suite run finalizer

- Added `TlsKeylogRunFinalizer` to rotate the active keylog file and purge expired rotations after a run.
- Wired the manual DPI suite controller to run the finalizer with the effective validated keylog path when debug mode is enabled and Privacy Mode is off.
- Added focused JVM tests for finalizer rotate/purge behavior and app-side effective path validation.
- Verification:
  - `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizerTest -Pripdpi.skipNativeBuild=true`
  - `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.DiagnosticsTlsKeylogPathTest -Pripdpi.skipNativeBuild=true`
- Remaining before close: TLS engine callback wiring and manual Wireshark decrypt proof.
