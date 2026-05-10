---
title: Add HTTP Compression Prober for gzip/deflate/brotli/zstd Support
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add HTTP Compression Prober for gzip/deflate/brotli/zstd Support #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `HttpCompressionProber` that, given a target URL, sends `GET` with each of `Accept-Encoding: gzip`, `deflate`, `br`, `zstd`, attempts to decompress the response body, and reports per-codec verdict: `OK`, `NOT_SUPPORTED`, `EOF_BEFORE_MIN`, `TIMEOUT`, `CONN_ERR`.

## Context

Android port of `utils/http_compression_prober.py`. The diagnostic value: certain bypass strategies require the destination server to support specific compression codecs (e.g. some CDN bypass tricks rely on `br` to confuse byte-counting middleboxes; some Telegram media servers use `zstd` for newer chunked transfers). Knowing which codecs a target supports informs strategy selection.

The probe is also useful as an indirect TCP 16-20 signal: if `gzip` works but `br` (brotli) cuts off at exactly 16-20KB, that's a hint TSPU is byte-counting compressed bytes specifically — which informs strategy choice.

**Per-codec probe:**
1. Send `GET <url>` with `Accept-Encoding: <codec>`, plus a generic Chrome User-Agent
2. Read response; if `Content-Encoding != <codec>` → `NOT_SUPPORTED`
3. Stream-decompress via codec-appropriate decoder; track decompressed bytes
4. Verify decompressed bytes ≥ `compr_min` (default 1024) — small responses can't validate compression
5. If decompression succeeds with ≥ min bytes → `OK`
6. If stream EOFs before min → `EOF_BEFORE_MIN`
7. Timeout → `TIMEOUT`
8. Connect/read error → `CONN_ERR`

**Required Android decoders:**
- `gzip`: `java.util.zip.GZIPInputStream` (built-in)
- `deflate`: `java.util.zip.Inflater` (built-in)
- `br`: Brotli decoder — use `org.brotli:dec` from Brotli's official Java port (small dep)
- `zstd`: zstd decoder — use `com.github.luben:zstd-jni` (~600KB native lib; gates this codec behind a config flag if APK size matters)

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/utils/http_compression_prober.py`

**RIPDPI placement:**
- Prober: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/HttpCompressionProber.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/CompressionProbeResult.kt`

## Acceptance criteria

- [ ] `HttpCompressionProber.probeAll(url: String, timeoutMs: Long = 10000, comprMin: Int = 1024): Map<Codec, CompressionProbeResult>`
- [ ] `Codec` enum: `GZIP`, `DEFLATE`, `BROTLI`, `ZSTD`
- [ ] `CompressionProbeResult`: `verdict (OK | NOT_SUPPORTED | EOF_BEFORE_MIN | TIMEOUT | CONN_ERR | INTERNAL_ERR)`, `httpStatus: Int?`, `compressedBytes: Long?`, `decompressedBytes: Long?`, `error: String?`
- [ ] All 4 codecs probed in parallel via `coroutineScope { async { ... } }`
- [ ] Per-codec request: `Accept-Encoding: <codec>` only (no other codecs); generic Chrome UA
- [ ] Response `Content-Encoding` header verified to match requested codec; mismatch → `NOT_SUPPORTED`
- [ ] Decompression streamed (does not buffer full body in memory); chunk size 8KB
- [ ] `comprMin` threshold enforced; below → `EOF_BEFORE_MIN`
- [ ] Brotli decoder via `org.brotli:dec` dependency added to `core/diagnostics/build.gradle.kts`
- [ ] zstd decoder gated behind `compressionProbeIncludeZstd` setting (default OFF — APK size); when OFF, `ZSTD` returns `verdict = NOT_SUPPORTED` with `error = "ZSTD probe disabled"`
- [ ] Unit tests with `MockWebServer` returning each codec; assert decompression and verdict

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/HttpCompressionProberTest.kt`:
     - `gzip_with_full_payload_returns_ok()` — `MockWebServer` returns gzipped 4KB body with `Content-Encoding: gzip`; assert `GZIP → OK`; fails until prober exists
     - `deflate_with_payload_returns_ok()` — `Content-Encoding: deflate`; assert `DEFLATE → OK`
     - `brotli_with_payload_returns_ok()` — `Content-Encoding: br`; assert `BROTLI → OK`
     - `unsupported_codec_returns_not_supported()` — server returns identity (uncompressed) for `Accept-Encoding: br`; assert `BROTLI → NOT_SUPPORTED`
     - `payload_below_compr_min_returns_eof_before_min()` — server returns 200 bytes gzipped; `comprMin = 1024`; assert `EOF_BEFORE_MIN`
     - `timeout_returns_timeout()` — server hangs; assert `TIMEOUT`
     - `parallel_probes_complete_independently()` — instrument; assert all 4 codecs return verdicts in single call
     - `zstd_disabled_setting_returns_not_supported_without_network()` — `compressionProbeIncludeZstd = false`; assert no network call for `ZSTD`, returns `NOT_SUPPORTED`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `HttpCompressionProber`, codec decoders, settings entry
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract per-codec probe into `probeOne(url, codec): CompressionProbeResult`; share decoder selection via `Codec.decoder()`

## Definition of done

All 8 unit tests green. Compression matrix surfaced in DiagnosticsScreen as a per-target add-on card. zstd opt-in setting in detection settings.
