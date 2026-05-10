---
title: Add Telegram Throughput and DC Reachability Speed Test
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: [add-dpi-error-classifier]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Telegram Throughput and DC Reachability Speed Test #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `TelegramSpeedTest` that runs three concurrent probes — a 30.97 MB media download from `telegram.org`, a 10 MB upload to DC2 raw IP, and TCP-pings to all 5 Telegram DC IPs — with stall detection and throughput measurement, surfaced in the existing `DiagnosticsTelegramCards` UI.

## Context

Android port of dpi-detector's Test 6 (`run_telegram_test`). Russian ISPs frequently throttle (not block) Telegram traffic: the connection succeeds but data transfer stalls partway. Pure reachability checks miss this; the test needs to measure actual throughput over a realistic payload size and detect mid-stream stalls.

**Three concurrent probes:**

1. **Download** — `GET https://telegram.org/img/Telegram200million.png` (~30.97 MB); read in 64KB chunks; per-second ticker tracks bytes; stall = ≥10s with zero data received → abort and classify
2. **Upload** — `POST https://149.154.167.220:443/upload` (DC2 raw IP) with a 10MB stream of zero bytes (16KB chunks); the endpoint silently accepts whatever it gets; same stall-detection ticker
3. **DC Reachability** — TCP `connect()` (no TLS, no HTTP) to each of 5 DC IPs port 443; measure RTT; report N/5 reachable

**Stall detection:**
- `STALL_TIMEOUT = 10s` — no data received in 10s → abort with status `STALLED`
- `TOTAL_TIMEOUT = 60s` — total elapsed cap

**Status classification per probe:**
- `0 bytes` transferred → `BLOCKED`
- ≥98% of expected size → `OK`
- last data ≥ 10s ago → `STALLED`
- in-progress but slow → `SLOW`

**Aggregate verdict:**
- DC reachability `0/5` AND (download `BLOCKED` OR upload `BLOCKED`) → `BLOCKED`
- Either probe `STALLED`/`SLOW` → `SLOW` (throttling)
- DC reachability between 1 and 4 → `PARTIAL`
- Both `OK` AND DC `5/5` → `OK`

**Reference:** `/Users/po4yka/GitRep/dpi-detector/core/telegram_scanner.py`

**Note:** `DiagnosticsTelegramCards.kt` already exists in `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/`. This task wires real probe results into it (currently shows placeholder data).

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/TelegramSpeedTest.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/TelegramTestResult.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/TelegramDcPinger.kt`

## Acceptance criteria

- [ ] `TelegramTestResult`: `verdict (OK | SLOW | PARTIAL | BLOCKED | ERROR)`, `download: ProbeStats`, `upload: ProbeStats`, `dcResults: List<DcReachability>`
- [ ] `ProbeStats`: `status (OK | STALLED | SLOW | BLOCKED | ERROR)`, `bytesTotal: Long`, `durationMs: Long`, `avgBps: Long`, `peakBps: Long`, `dropAtSec: Int?`
- [ ] `DcReachability`: `label (DC1..DC5)`, `ip`, `reachable: Boolean`, `rttMs: Long?`
- [ ] All 5 DC IPs from constant: `149.154.175.53` (DC1), `149.154.167.51` (DC2), `149.154.175.100` (DC3), `149.154.167.91` (DC4), `91.108.56.130` (DC5)
- [ ] Download: 64KB chunked read; per-second ticker emits `Flow<DownloadProgress>` for UI
- [ ] Upload: 16KB chunked stream of zeros to `149.154.167.220:443/upload`; total 10MB; per-second ticker
- [ ] Stall detection: 10s without data → abort with `STALLED`; record `dropAtSec`
- [ ] Total timeout: 60s cap per probe
- [ ] DC ping: raw `Socket.connect(InetSocketAddress(ip, 443), DC_PING_TIMEOUT_5s)`; measure RTT; close
- [ ] All three probes via `coroutineScope { async { ... } }` with `awaitAll`
- [ ] Verdict aggregation as specified above
- [ ] Wired into `DiagnosticsTelegramCards.kt` (existing UI), replacing placeholder data
- [ ] Unit tests: mock `OkHttpClient` for download/upload; mock socket for DC ping; assert verdict aggregation

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/TelegramSpeedTestTest.kt`:
     - `download_completes_in_full_returns_ok_status()` — mock returns 30.97MB; assert `download.status == OK`; fails until test exists
     - `download_stalls_after_5_seconds_returns_stalled()` — mock yields 1MB then hangs; assert `STALLED`, `dropAtSec ≈ 5`
     - `download_zero_bytes_returns_blocked()` — mock returns immediate EOF; assert `BLOCKED`
     - `upload_completes_returns_ok()` — mock accepts full 10MB; assert `upload.status == OK`
     - `dc_ping_all_5_reachable_returns_5_5()` — mock socket succeeds for all 5 IPs; assert `dcReachable == 5`
     - `dc_ping_dc2_only_blocked_returns_4_5()` — mock socket fails only for DC2; assert `dcReachable == 4`
     - `verdict_blocked_when_dc_zero_and_download_blocked()` — DC `0/5`, download blocked; assert `verdict == BLOCKED`
     - `verdict_slow_when_download_stalled()` — download `STALLED`, DC `5/5`; assert `verdict == SLOW`
     - `verdict_partial_when_dc_3_of_5()` — DC `3/5`, both probes OK; assert `verdict == PARTIAL`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 9 fail
3. **Implement** — `TelegramSpeedTest`, `TelegramDcPinger`, result models; wire into `DiagnosticsTelegramCards`
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract stall-watcher into reusable `ThroughputWatcher(stallTimeout, totalTimeout)`

## Definition of done

All 9 unit tests green. `TelegramSpeedTest` powers `DiagnosticsTelegramCards`; live progress emitted via `Flow<TelegramTestProgress>`. DC ping panel shows all 5 DCs with RTT.
