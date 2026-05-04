---
title: Remove PCAP from normal diagnostics archives and harden developer-analytics.json
type: task
status: backlog
area: android
priority: high
owner: Senior Android Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Remove PCAP from normal diagnostics archives and harden developer-analytics.json #repo/RIPDPI #area/android #status/backlog ⏫

## Objective
Bring `DefaultDiagnosticsArchiveExporter` and `developer-analytics.json` content into compliance with the AppSec decision on POY-14 (which adopts the CTO boundary in POY-13). PCAP files must not be auto-attached to normal archives, and `developer-analytics.json` must drop fields that have no user-facing disclosure.

## Context
AppSec POY-14 verdict: changes_requested. The current `DefaultDiagnosticsArchiveExporter.createArchive` calls `selection.copy(pcapFiles = fileStore.getRecentPcapFiles())` for every archive request type (`SHARE_ARCHIVE`, `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS`), and `DiagnosticsArchiveCsvEntryBuilder.buildCsvEntries` writes each `.pcap` byte-for-byte as a zip entry. This contradicts README:56 and the POY-13 boundary. Separately, `DefaultDeveloperAnalyticsSource` ships `lastPanicBacktrace`, `nativeLibDigests`, `breadcrumbs`, `pcapManifest`, and a config diff (including `rootModeEnabled`, `enableCmdSettings`) inside `developer-analytics.json` for every archive without disclosure on `DataTransparencyScreen`.

User story:
As a RIPDPI user sharing a diagnostics archive, I want my exported zip to never silently include packet captures, native panic backtraces, or build digests, so that what I share matches the on-screen disclosure.

Affected surface:
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveExporter.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveCsvEntryBuilder.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveFileStore.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveModels.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRenderer.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DeveloperAnalyticsModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/diagnostics/DefaultDeveloperAnalyticsSource.kt`

## Acceptance criteria
1. F-01 (Critical): For all four `DiagnosticsArchiveReason` values, the rendered zip contains zero entries with the `.pcap` extension and `manifest.includedFiles` lists no `.pcap`. The exporter must not call `getRecentPcapFiles()` on the normal share/save paths; if a future explicit "Share PCAP" action lands, it does so via a separate code path.
2. F-03 (High): `developer-analytics.json` for normal archive reasons must omit `lastPanicBacktrace`, `nativeLibDigests`, `breadcrumbs`, `pcapManifest`, and `effectiveConfigDiff` until each is re-introduced under an AppSec-approved allow-list (out of scope for this issue).
3. F-06 (Medium): Add a redaction pass over `probe-results.csv` (`detailJson`, `target`) and `native-events.csv` (`message`) that strips IP/SSID/MAC/email/path-style strings, OR document in code why those columns can never carry such values from upstream sanitisation.
4. F-08 (Low): Remove the duplicate logcat tail in `DefaultDeveloperAnalyticsSource.readLogcatTail` (or remove the `LogcatSnapshotCollector` path) so only one logcat capture lands in the archive.
5. One-time cleanup: on first launch of the build that lands these changes, invoke `cleanupPcapFiles()` ignoring the 24h window when `rootModeEnabled == false`, so pre-upgrade `.pcap` files cannot survive into a build that forbids auto-attach.

## Required verification
- Add tests `createArchive_share_archive_excludes_pcap_when_recent_pcap_files_exist` and the equivalent for `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS` in `DiagnosticsArchiveExporterTest`.
- Extend `DiagnosticsArchiveRendererTest` with assertions on `manifest.includedFiles`, `developer-analytics.json` absence of forbidden fields, and a redaction sweep on the rendered byte buffers.
- Add or extend `DiagnosticsArchiveRedactorTest` with a fuzz-style test that constructs a `NetworkSnapshotModel` with non-default sensitive fields and asserts no verbatim original value reaches the encoded JSON.
- AppSec re-review on a single re-review request once F-01..F-04 are addressed.

Privacy implication:
High. Closing F-01 is a release-blocker for AppSec re-approval.

Rollback note:
Reverting reintroduces the auto-attach. Do not revert without AppSec approval. No data migration is required because `.pcap` files are kept in `cacheDir/diagnostics` and Android handles cache cleanup on uninstall.

Non-goals:
- No copy or docs changes (owned by POY-15).
- No QA gate definition (owned by POY-16).
- No new "Share PCAP" action (would need its own AppSec review per POY-14 §4.4).
- No re-introduction of any field listed in F-03 without an AppSec allow-list issue.

## Definition of done
The four normal archive reasons produce archives with no `.pcap` entries and a sanitised `developer-analytics.json`; tests above are green; AppSec re-approves on re-review of POY-14.
