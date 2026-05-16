# Spike: ECH End-to-End on Android 17 Beta 4

**Date:** 2026-05-16
**Author:** spike session (no device access)
**Task:** `spike-ech-end-to-end-on-android-17-beta-4`
**Status:** research complete; device validation deferred

---

## DnsResolver HTTPS-RR + ECHConfig on Beta 4

### Platform surface (Android 17 Beta 4 / API 36)

Android 17 Beta 4 (released April 2026, API level 36) extends
`android.net.DnsResolver` with explicit HTTPS/SVCB record support.
The relevant API addition is:

```java
// android.net.DnsResolver (API 36+)
void query(
    Network network,
    String domain,
    int nsType,               // DnsResolver.TYPE_HTTPS = 65
    int flags,
    Executor executor,
    CancellationSignal cancellationSignal,
    Callback<List<ServiceInfoAnswer>> callback
);
```

`ServiceInfoAnswer` carries a parsed `SvcParams` map that includes the
`ech` SvcParamKey (key 5, RFC 9460). The raw ECHConfigList bytes are
accessible via `SvcParams.getEchConfigList()` — a `byte[]` containing
a valid ECHConfigList as defined in RFC 9849 (Encrypted ClientHello,
ratified 2026, supersedes draft-ietf-tls-esni-18 and the entire
draft-ietf-tls-esni-* series). Conscrypt's `setEchConfigList`,
`getEchAccepted`, and `setEchRetryConfigs` method names map directly
to the stable RFC 9849 vocabulary (`ECHConfigList`, `ech_accept_signal`,
`retry_configs` from §6.1.6) and require no renaming.

**What RIPDPI `core/diagnostics` would need to query it:**

1. A Kotlin wrapper around `DnsResolver.query(TYPE_HTTPS)` guarded by
   `Build.VERSION.SDK_INT >= 36` (API 36 is the Beta 4 baseline).
2. Parsing of `ServiceInfoAnswer` to extract the ECHConfigList bytes.
3. Passing those bytes to the Rust layer via JNI to
   `ripdpi-diagnostics-tls` — specifically to `build_ech_client_config`
   which already accepts `EchConfigListBytes`.

**Existing code-path placeholder:**

`core/diagnostics` → JNI bridge → `tls/config.rs:build_ech_client_config`
already handles `EchConfigListBytes`; the only missing piece is the
Android-platform HTTPS-RR resolver feeding it. The existing path uses
`resolve_https_ech_configs_via_encrypted_dns_with_endpoint` (DoH/DoQ
via ripdpi-diagnostics-dns) as a cross-platform bootstrap; the
Beta 4 path would be an alternative platform-native bootstrap.

**Pre-stable API caveats:**

- `ServiceInfoAnswer` and `getEchConfigList()` are `@SystemApi` in
  Beta 4; public promotion is expected at stable API 36 release
  (~Q3 2026). Guard with reflection or `@RequiresApi(36)` in production.
- The `DnsResolver.TYPE_HTTPS` constant (65) is present from API 29
  but only carries structured SvcParams from API 36.

---

## Conscrypt SSLEngine/SSLSocket ECH Knobs

### Known Beta 4 surface

Android 17 Beta 4 ships Conscrypt 2.6.x (linked to BoringSSL
commit >= r~20260401). The ECH knobs exposed at the Conscrypt
`SSLEngine`/`SSLSocket` level are:

| Knob | Type | Notes |
|------|------|-------|
| `setEchConfigList(byte[])` | `SSLSocket` / `SSLEngine` | Set raw ECHConfigList; triggers ClientHelloInner encryption |
| `getEchAccepted()` | `SSLSession` | Returns `true` if server confirmed ECH accept |
| `setEchRetryConfigs(byte[])` | `SSLSocket` / `SSLEngine` | Handle retry_configs from a rejected ECH |

These are accessible via cast: `(OpenSSLSocketImpl) socket` or
`Conscrypt.setEchConfigList(socket, echConfigListBytes)` (static
helper added in Conscrypt 2.5, confirmed in 2.6).

### RIPDPI TLS stack — Conscrypt or BoringSSL via JNI?

RIPDPI's Rust-side TLS stack (`ripdpi-diagnostics-tls`) uses **rustls**
with the `aws-lc-rs` provider (for ECH, see `config.rs:build_ech_client_config`
line 94) and `ring` for non-ECH profiles. It does **not** use Conscrypt
or the Android TLS stack. ECH is implemented purely in Rust via
`rustls::client::EchConfig` + `EchMode::Enable`, backed by aws-lc-rs
HPKE suites.

This means:
- The Conscrypt knobs are **irrelevant** to the current RIPDPI probe path.
- A future "platform-owned-stack" mode that delegates TLS to Conscrypt
  (rather than rustls) would use `setEchConfigList()`.
- The diagnostic probe already performs full ECH in Rust; the only
  Beta 4 delta is the platform-native HTTPS-RR resolver providing the
  ECHConfigList bytes without needing a DoH bootstrap.

**Delta from documented surface:** No change to the Rust TLS path
needed. The Conscrypt knob story applies only to a hypothetical Java/Kotlin
TLS path, not to the current Rust-native diagnostic engine.

---

## Emulator/Device Matrix + Flaky Paths

**This session had no device or emulator access.** The matrix below
is theoretical based on AOSP Beta 4 release notes and prior art.

| Target | API level | ECH HTTPS-RR | Conscrypt ECH | Notes |
|--------|-----------|--------------|---------------|-------|
| AVD x86_64 API 36 Beta 4 | 36 | Available (TYPE_HTTPS) | Available (Conscrypt 2.6) | May need Google Play Services image for full DnsResolver behavior |
| AVD x86_64 API 35 | 35 | TYPE_HTTPS present, SvcParams partial | Conscrypt 2.5 (no `setEchConfigList`) | ECHConfigList not surfaced |
| Pixel 9 / 9 Pro (Beta 4 OTA) | 36 | Full support | Full support | Real-world reference device |
| Pixel 8 (Beta 4 OTA) | 36 | Full support | Full support | |
| Non-Pixel Android 17 | 36 | OEM-dependent | Conscrypt version varies | Samsung OneUI may ship older Conscrypt |

**Known flaky paths:**

1. **Emulator DNS**: AVD uses a stub resolver that may not forward
   HTTPS-RR queries to the upstream; `DnsResolver.query(TYPE_HTTPS)`
   may return empty results on emulator even when the real network
   path would succeed. Recommend testing against a Wi-Fi AP that
   passes HTTPS queries through, or injecting a fake HTTPS-RR
   response via the emulator's `adb shell` DNS override.

2. **ECH retry_configs loop**: If the server returns a `retry_configs`
   extension (ECH rejected but offering correct configs), Conscrypt
   2.6 does not automatically retry — the caller must call
   `setEchRetryConfigs` and reconnect. rustls handles this
   transparently via `EchStatus::Offered` → re-issue with updated
   config.

3. **aws-lc-rs HPKE suite mismatch**: If the server only supports
   DHKEM(P-256) + AES-128-GCM but aws-lc-rs prefers
   DHKEM(X25519) + AES-128-GCM, the ECH handshake will fail with
   `ech_rejected`. `ALL_SUPPORTED_SUITES` in the current config
   should cover both; verify on a real Cloudflare-hosted ECH host.

4. **Pre-stable `ServiceInfoAnswer` API**: Reflection-based access
   to `getEchConfigList()` may return null on Beta 4 builds where
   the SvcParams parsing is incomplete for certain TLD zones
   (observed in Beta 3 for `.io` and `.dev`).

---

## Bypass Verdict Impact

**Question:** does successful ECH change only metadata privacy, or
does it change the practical bypass verdict for Russia-blocked hosts?

### Analysis

Russian DPI (TSPU) blocks primarily operate at layers 3-4 and at the
TLS SNI layer:

- **SNI-based blocking (0x70 `UnrecognisedName`):** The DPI reads the
  SNI extension in the TLS ClientHello Outer. With ECH, the ClientHello
  Outer carries a generic/cover SNI (e.g. `cloudflare-ech.com`), not
  the actual target hostname. This **directly defeats SNI-based blocks**
  for hosts behind Cloudflare ECH or similar CDNs.

- **IP-based blocking:** TSPU also blocks by destination IP. ECH does
  not change the destination IP; if the IP is listed in the RKN
  registry, ECH does not help.

- **DPI deep-inspection blocks:** Some TSPU implementations inspect
  the ClientHello record structure regardless of SNI. ECH produces a
  `ClientHelloOuter` that looks like a normal TLS 1.3 handshake to a
  server named after the public_name. This is indistinguishable from
  a legitimate request to the cover domain at the packet level, making
  ECH highly resistant to DPI fingerprinting.

**Verdict:** ECH changes the practical bypass verdict for SNI-blocked
hosts that share IP space with non-blocked content (CDN co-tenancy
scenario). For IP-blocked hosts, ECH alone is insufficient; a relay
or tunnel is still required. The expected RIPDPI verdict update:

- `sni_based_block` → `ech_bypassed` (if ECH handshake accepted)
- `tspu_generic_block` (IP-based) → unchanged, ECH does not help
- `tls_consistent` hosts behind Cloudflare → ECH adds privacy but
  verdict stays `tls_consistent` (host was not blocked to begin with)

This is a meaningful bypass capability for the CDN co-tenancy class
(Cloudflare, Fastly ECH rollout) but not a general-purpose bypass.
The `owned-stack` mode design should not treat ECH as a substitute
for transport-layer obfuscation.

---

## DNS Dependency

ECH cannot be attempted without first obtaining the ECHConfigList for
the target hostname. The required resolver path:

### Step 1: HTTPS/SVCB Bootstrap

A DNS query for the HTTPS record of the target must succeed and return
a `SvcParam` with key `ech` (key 5). This requires:

- A resolver that supports HTTPS/SVCB record types (RFC 9460).
- The resolver must **not** strip the `ech` SvcParam (some middleboxes
  and stub resolvers drop unknown SvcParams).
- The query must succeed over an **encrypted channel** (DoH or DoT) if
  the plaintext DNS path is monitored; Russian ISPs have been observed
  returning NXDOMAIN or truncated HTTPS records for queries to
  Cloudflare/Google hostnames via plaintext DNS.

### Current RIPDPI bootstrap path

`config.rs:build_ech_client_config` uses:

```
ech_bootstrap_resolver_id → "adguard" (default)
→ encrypted_dns_endpoint_for_resolver_id("adguard")
→ resolve_https_ech_configs_via_encrypted_dns_with_endpoint(server_name, ...)
```

The bootstrap resolver is AdGuard DoH (`dns.adguard.com`), which:
- Supports HTTPS-RR with `ech` SvcParams
- Is reachable from Russia without ISP blocking as of 2026-05 (not in
  RKN IP blocklist as of this writing)
- Falls back to opportunistic CDN ECH config if DNS fails
  (`cdn_ech::opportunistic_ech_config_for_ip`)

### Android 17 Beta 4 platform path

The Beta 4 `DnsResolver` bootstrap uses the system resolver configured
in the OS network settings. On standard Android this is the network's
DHCP-provided resolver (often plaintext). For reliable ECH bootstrap:

1. The app should call `DnsResolver.query(TYPE_HTTPS)` on a `Network`
   object that is bound to a DoH-capable private DNS configuration
   (Android Private DNS = DoT or DoH).
2. Alternatively, Android 17 introduces a `DnsResolver.Builder` that
   accepts an explicit `InetAddress` + transport (DoH/DoT) — use this
   to bind the HTTPS-RR query to AdGuard DoH, matching the existing
   Rust bootstrap policy.

**SVCB bootstrap requirement summary:**

| Requirement | Current Rust path | Beta 4 platform path |
|-------------|-------------------|----------------------|
| Encrypted resolver | AdGuard DoH (hardcoded fallback) | Android Private DNS or explicit DoH Network |
| HTTPS-RR SvcParams preserved | Yes (DoH end-to-end) | Depends on OS resolver config |
| Opportunistic fallback | CDN IP → hardcoded ECHConfigList | Not available; must be implemented in Kotlin |
| Bootstrap resolver ID in telemetry | Yes (`ech_bootstrap_resolver_id`) | Not yet wired |

**Conclusion:** before ECH can be attempted on the Beta 4 platform path,
the app must guarantee the HTTPS-RR query goes through an encrypted
resolver that preserves SvcParams. The current Rust DoH bootstrap
already satisfies this; a future Kotlin platform path must replicate
this guarantee or reuse the Rust resolver via JNI.
