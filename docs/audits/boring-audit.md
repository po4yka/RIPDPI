# Feasibility Audit: `boring` Crate for Chrome-Compatible TLS Fingerprinting

**Date:** 2026-03-24
**Status:** Research complete, pending decision
**Scope:** JA3/JA4 fingerprint evasion via BoringSSL bindings

---

## 1. Current TLS Fingerprint Situation

### Real TLS connections (rustls)

All live TLS connections use **rustls 0.23.37** with the `ring` crypto backend:

| Consumer | File | Customization |
|----------|------|---------------|
| DNS resolver (DoT/DoH) | `ripdpi-dns-resolver/src/transport.rs` | None -- default cipher suites |
| Monitor probes | `ripdpi-monitor/src/tls.rs` | Protocol version only (TLS 1.2 / 1.3) |
| WebSocket tunnel | `ripdpi-ws-tunnel/src/connect.rs` | None -- tungstenite defaults |

rustls produces a **non-browser JA3/JA4 hash**. Its cipher suite ordering,
extension set, and ALPN values are distinct from any major browser. A DPI system
with a JA3/JA4 database can trivially classify these connections as
"rustls / non-browser Rust client."

**No cipher suite reordering, extension manipulation, or GREASE injection is
performed on real connections.** The `ClientConfig::builder()` call uses
rustls defaults throughout.

ECH (Encrypted Client Hello) is blocked because `ring` lacks HPKE primitives.
A TODO at `ripdpi-monitor/src/tls.rs:35-36` notes this.

### Fake/probe packets (binary blobs)

DPI evasion currently relies on pre-captured ClientHello blobs from real
browsers (`ripdpi-packets/src/fake_profiles/`):

| Profile | Browser | Key signals |
|---------|---------|-------------|
| `IanaFirefox` | Firefox ~115-125 | 31 cipher suites, no GREASE, TLS 1.0-1.3 |
| `GoogleChrome` | Chrome ~115-120 | ECH, ALPS, 17 cipher suites |
| `VkChrome` | Chrome ~110-124 | GREASE, 16 cipher suites |
| `SberbankChrome` | Chrome ~110-124 | GREASE, 16 cipher suites |
| `RutrackerKyber` | Chrome >= 124 | ML-KEM-768, ECH, GREASE |

These blobs are byte-accurate copies of real browser ClientHellos. The nom
parser (`tls_nom.rs`) extracts offsets for SNI mutation; the mutation layer
(`tls.rs`) can rewrite hostnames, remove cipher suites, and adjust padding.

**Gap:** Fake packets only cover the initial ClientHello of desync/fake
sequences. The actual proxied TLS connection still uses rustls and carries
its own detectable fingerprint. A sophisticated DPI system can correlate the
two and flag the mismatch.

### No JA3/JA4 computation code

The codebase contains no JA3/JA4 hash computation or fingerprint comparison
logic. Browser attribution is manual, documented in `PROVENANCE.md`.

---

## 2. `boring` vs `rustls` Comparison

### 2.1 JA3/JA4 Fingerprint Accuracy

| | boring (BoringSSL) | rustls |
|--|-------------------|--------|
| Fingerprint identity | Chrome-identical | Unique non-browser hash |
| GREASE | Yes (Chrome-style randomization) | No |
| ECH | Yes (BoringSSL supports HPKE) | No (ring backend) |
| Post-quantum (ML-KEM) | Yes (default in v5.x) | Experimental |
| Extension ordering | Chrome-native | rustls-native |
| Cipher suite ordering | Chrome-native | rustls-native |

**boring is the only path to a Chrome-identical JA3/JA4 fingerprint.** Multiple
production projects prove this: wreq (100+ browser profiles), rquest, and
reqwest-impersonate all use boring to impersonate Chrome/Firefox/Safari.

rustls cannot impersonate browsers. Its API does not expose cipher suite
ordering or extension ordering. No known project achieves browser-identical
fingerprints with rustls.

### 2.2 Binary Size

Current baseline (arm64-v8a, stripped release):

| Library | Current size |
|---------|-------------|
| `libripdpi.so` | 783 KB |
| `libripdpi-tunnel.so` | 1,147 KB |
| **Total per ABI** | **1,930 KB** |

Estimated boring impact (arm64-v8a, stripped, LTO):

| Component | Estimate |
|-----------|----------|
| BoringSSL (libcrypto + libssl) | 1,000 - 1,600 KB |
| `OPENSSL_SMALL=1` savings | -44 KB (removes P-256 precomputed table) |

If boring replaces rustls in one library (e.g., `libripdpi.so`):
- Remove rustls+ring contribution (~300-400 KB)
- Add BoringSSL contribution (~1,000-1,600 KB)
- **Net increase: ~600-1,200 KB per ABI**

This would push `libripdpi.so` from ~783 KB to ~1,400-2,000 KB. The 15% CI
size gate would need a baseline update.

If boring is added alongside rustls (dual-stack), the increase is the full
~1,000-1,600 KB with no offset.

### 2.3 Build Complexity

| | boring | rustls |
|--|--------|--------|
| Language | C/C++ (vendored BoringSSL) + Rust bindings | Pure Rust |
| Build tool | CMake >= 3.22 (available from Android SDK) | cargo only |
| Compiler | C11 + C++17 (NDK clang) | rustc only |
| Go required | No (only for BoringSSL test suite) | N/A |
| Source vendoring | Git submodule (~30-50 MB) | crates.io |
| Clean build time | +3-8 min (BoringSSL C++ compilation) | Baseline |
| Incremental build | Fast (C++ objects cached) | Fast |

**RIPDPI currently has zero C/C++ dependencies.** Adding boring breaks this
invariant. The ELF contract verification (`verify_native_elfs.py`) would need
updating if BoringSSL pulls in additional NEEDED libraries (unlikely -- it
links statically).

### 2.4 Android NDK Cross-Compilation

boring-sys has **first-class Android support** in its build script:

- Explicit ABI mapping: `aarch64-linux-android` -> `arm64-v8a`, etc.
- Reads `ANDROID_NDK_HOME` environment variable
- Uses NDK's CMake toolchain file automatically
- Sets `CMAKE_SYSTEM_VERSION=21` (API level)

The existing `BuildRustNativeLibsTask` Gradle plugin already sets `CC_<TARGET>`
and `AR_<TARGET>` env vars per ABI. boring-sys should pick these up. The main
addition: `ANDROID_NDK_HOME` must be exported (currently only `ANDROID_SDK_ROOT`
is set in the Gradle task).

### 2.5 Maintenance and Security Updates

| | boring | rustls |
|--|--------|--------|
| Maintainer | Cloudflare | ISRG / Let's Encrypt |
| Latest release | v5.0.2 (2026-02-17) | v0.23.37 |
| Release cadence | ~monthly | ~monthly |
| Downloads | ~4M total, ~592K recent | ~30M+ |
| CVE response | Tracks BoringSSL upstream | Independent |
| FIPS | Yes (certified module) | No |

Both are actively maintained. boring tracks BoringSSL upstream, which receives
security patches from Google's Chrome team.

---

## 3. Recommendation

### Primary recommendation: boring for proxied connections + rustls for internal ops

**Use boring** for the SOCKS5 proxy's upstream TLS connections (the path
visible to DPI) and **keep rustls** for internal operations (DNS resolution,
monitor probes, WebSocket tunnel control plane).

Rationale:
- The proxy upstream connection is the only path where JA3/JA4 fingerprint
  matters to DPI. DNS and monitor traffic are either encrypted via other
  channels or are low-volume diagnostic probes.
- Dual-stack avoids a full migration. rustls remains the default; boring is
  scoped to the fingerprint-sensitive path.
- The wreq/rquest ecosystem provides proven Chrome profile data structures
  that can be referenced or adapted.

### Alternative: rustls-only with manual cipher suite tuning

rustls does not expose cipher suite or extension ordering APIs. The only
way to approximate a browser fingerprint with rustls is:

1. Fork or patch rustls to reorder extensions/cipher suites in the ClientHello
2. Add GREASE value injection
3. Manually maintain cipher suite lists matching Chrome releases

This is fragile, requires ongoing maintenance per Chrome release, and cannot
achieve byte-identical fingerprints (rustls's internal extension generation
differs structurally from BoringSSL). **Not recommended.**

### Scope of boring integration

```
ripdpi-ws-tunnel (Telegram WSS tunnel -- DPI-visible)
  └── boring (sync TLS via SslConnector, Chrome fingerprint)

ripdpi-dns-resolver (DoT/DoH -- internal)
  └── tokio-rustls (unchanged)

ripdpi-monitor (diagnostic probes -- low volume)
  └── rustls (unchanged)
```

The WebSocket tunnel is the primary DPI-visible TLS connection. `ripdpi-session`
is a protocol parser and does not establish TLS connections itself.

---

## 4. Experimental Plan

### Phase 1: Build feasibility (1-2 hours)

1. Add `boring` + `tokio-boring` as workspace dependencies behind a
   `chrome-fingerprint` feature flag
2. Build for `arm64-v8a` only:
   ```bash
   cd native/rust
   ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/29.0.14206865 \
     cargo build -p ripdpi-session --features chrome-fingerprint \
     --target aarch64-linux-android --profile android-jni
   ```
3. **Measure:**
   - Clean build time delta
   - `libripdpi.so` size delta
   - Additional NEEDED libraries in ELF (should be none)
   - Source tree size increase (vendored BoringSSL)

### Phase 2: Fingerprint verification (2-4 hours)

1. Create a minimal test binary that opens a TLS connection via boring
   to a test server
2. Capture the ClientHello with `tshark`
3. Compute JA3/JA4 hash and compare against Chrome's known hash for the
   same BoringSSL version
4. Verify GREASE, ECH, and extension ordering match

### Phase 3: Integration prototype (1-2 days)

1. Add a `TlsBackend` enum (`Rustls` | `Boring`) to `ripdpi-session`
2. Wire boring-based `ClientConfig` with Chrome cipher suite profile
3. Run the SOCKS5 proxy through a DPI test harness
4. Compare JA3/JA4 before/after

---

## 5. Risk Assessment

### Build pipeline risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| CMake version mismatch in CI | Medium | Pin CMake version in CI workflow; Android SDK bundles CMake 3.22+ |
| BoringSSL C++ compilation failure on NDK update | Medium | Pin BoringSSL version via boring-sys; test NDK updates in isolation |
| CI build time increase (+3-8 min per ABI, x4 ABIs) | Low | Feature flag; only build boring ABIs in release CI; sccache covers C++ |
| Git submodule fetch failure | Low | Vendor BoringSSL source or use `BORING_BSSL_SOURCE_PATH` |
| Symbol conflicts with other OpenSSL consumers | Low | boring `prefix-symbols` feature renames all BoringSSL symbols |

### Size budget risks

| Scenario | arm64-v8a delta | Within 15% gate? |
|----------|----------------|-------------------|
| boring replaces rustls in libripdpi.so | +600-1,200 KB | No -- baseline update required |
| boring added alongside rustls | +1,000-1,600 KB | No -- baseline update required |

Any boring adoption requires updating `native-size-baseline.json`. The ELF
NEEDED contract should remain unchanged (BoringSSL links statically).

### Operational risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Chrome fingerprint drift (new Chrome version = new JA3/JA4) | Medium | Track BoringSSL releases; update boring crate quarterly |
| BoringSSL CVE requires urgent update | Low | boring tracks upstream; Cloudflare patches promptly |
| Dual TLS stack increases attack surface | Low | boring handles untrusted network only; rustls handles trusted paths |
| NDK version bump breaks boring-sys | Medium | Test boring build in NDK update PRs before merging |

### Decision criteria for Go/No-Go

**Go** if Phase 1 shows:
- arm64 build completes without manual patches
- Size delta is under 2 MB per ABI
- No new NEEDED libraries in ELF
- Clean build time increase is under 10 minutes

**No-Go** if:
- boring-sys requires patching for NDK 29
- BoringSSL pulls in unexpected shared library dependencies
- Build time increase exceeds 15 minutes per ABI
