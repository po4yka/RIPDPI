# Cloudflare Degradation Classification Runbook

**Version:** 2026-05-16 **Area:** relay / Cloudflare path **Related enum:** `CloudflareDegradationClass` in `core/data/settings`

---

## Overview

Similar user-visible symptoms can arise from five distinct root causes. This runbook distinguishes them so the operator response is targeted: demote the Cloudflare path, rotate the hostname, fix the origin, patch the client profile, or redirect users to whitelist-mode guidance.

Sensitive live findings (hostnames, IPs, credentials, per-server health data) must be stored under `ops/live-infra/` in the `censorship-bypass` vault — never inline in this document.

---

## User-Visible Summary Strings

| Class | User-visible string |
|---|---|
| EdgeThrottling | degraded Cloudflare-like path |
| DomainBlocked | degraded Cloudflare-like path |
| OriginFailure | origin issue |
| ClientProtocolFailure | profile issue |
| WhitelistShutdown | network restricted |

---

## Class 1 — EdgeThrottling

**Symptoms**

- Connections succeed initially then degrade under load.
- Throughput drops to a fraction of baseline; latency spikes.
- HTTP 429 or 503 responses with `cf-ray` headers present.
- Behaviour is rate-dependent: short idle periods restore connectivity.

**Payload-level checks**

1. Send a ≥1 MB payload through the Cloudflare path and measure throughput at the 10 s, 30 s, and 60 s marks.
2. Capture the full HTTP response body for any non-2xx status; look for `retry-after` or `x-ratelimit-*` headers.
3. Repeat with a small probe (≤4 KB) immediately after the large one; if the small probe succeeds, throttling is per-flow or per-byte-budget.

**Non-Russian control checks**

- Run the same throughput probe from a non-Russian IP (EU or US exit).
- If throughput is unaffected on the control path, the throttle is geo-specific; otherwise it is a global edge issue.

**Response**

Disable Cloudflare auto-selection and rotate to a non-Cloudflare relay. If the throttle is global, escalate to origin/edge review.

---

## Class 2 — DomainBlocked

**Symptoms**

- TCP connection to the Cloudflare IP succeeds; TLS handshake fails or is reset mid-flight.
- DNS resolution returns a Cloudflare IP but HTTPS returns a block page (HTTP 451, 403, or a known ISP block page body).
- Only specific hostnames are affected; other Cloudflare-hosted names work from the same network.

**Payload-level checks**

1. Perform a TLS handshake and inspect the SNI echo in the server hello; compare against what was sent.
2. Fetch a ≥100 KB resource via HTTPS and inspect the full response body — ISP block pages often appear in the first 2 KB but only in the untruncated response.
3. Attempt the same hostname over a non-Cloudflare resolver (DoH to `9.9.9.9`) to rule out DNS-level tampering.

**Non-Russian control checks**

- Test the hostname from a non-Russian network. If reachable, the block is geographically scoped.
- Check OONI Explorer for recent measurements against the same hostname from the same ASN.

**Response**

Rotate the hostname. Record the blocked hostname in `ops/live-infra/` under the relevant server entry. Consider adding hostname rotation logic in the auto-selection policy.

---

## Class 3 — OriginFailure

**Symptoms**

- Cloudflare itself responds with HTTP 502 or 504 (`cf-error-type: origin_unreachable` or similar).
- Connections succeed from non-Russian networks via the same Cloudflare path, ruling out DPI.
- Error is consistent across hostnames served by the same origin.

**Payload-level checks**

1. Fetch a ≥1 MB payload and confirm the error appears regardless of payload size (rules out Cloudflare edge throttling).
2. Inspect the `cf-ray` and `x-cache` headers: a 502/504 with a `cf-ray` present but no `age` header indicates the request reached Cloudflare but the origin did not respond.

**Non-Russian control checks**

- Probe the origin directly (bypassing Cloudflare) from a non-Russian IP. If direct origin is also unreachable, the fault is in the origin server, not censorship.
- If direct origin is reachable but Cloudflare reports 502, the Cloudflare–origin route is broken (possible IP block or firewall misconfiguration at the origin).

**Response**

Do not disable the Cloudflare path — the path itself is functional. Fix or restart the origin service; update the server record in `ops/live-infra/`. Re-enable auto-selection once the origin is healthy.

---

## Class 4 — ClientProtocolFailure

**Symptoms**

- TLS handshake fails with a certificate error, protocol version mismatch, or cipher-suite negotiation failure.
- Error is reproducible across networks and ISPs; non-Russian control path exhibits the same failure.
- Downgrading or upgrading the profile (e.g., changing TLS version or SNI override) resolves the error.

**Payload-level checks**

1. Capture the full TLS ClientHello and ServerHello using a local packet capture or a debug build's TLS log.
2. Confirm whether the certificate chain validates against the system trust store.
3. Send a minimal TLS probe without any custom extensions; if it succeeds, a custom extension in the client profile is causing rejection.

**Non-Russian control checks**

- Test from a non-Russian IP with the same profile. If the failure persists, the issue is client-side, not censor-side.

**Response**

Update the client profile configuration. Do not disable Cloudflare auto-selection unless the profile cannot be corrected immediately. Store the corrected profile parameters in `ops/live-infra/`.

---

## Class 5 — WhitelistShutdown

**Symptoms**

- All Cloudflare IPs are unreachable via TCP from the affected network.
- ICMP and DNS to Cloudflare ranges fail or are intercepted.
- Affects all hostnames and all ports (80, 443, 2053, 8443, etc.).
- Other non-whitelisted services also fail; whitelisted Russian services remain reachable.

**Payload-level checks**

1. Attempt a raw TCP connect (not TLS) to `203.0.113.x` (documentation range) to establish baseline reachability.
2. Attempt TCP connects to three distinct Cloudflare anycast prefixes. If all three fail from the affected ASN, network-level blocking is in effect.
3. Compare response with a full TLS payload versus a bare TCP SYN — if SYN gets no response the block is at the routing/BGP or firewall layer, not DPI.

**Non-Russian control checks**

- Confirm Cloudflare IPs are reachable from EU/US exits; if so, the block is network-level and Russia-specific.
- Check RIPE RIS or BGPmon for route withdrawals affecting Cloudflare prefixes within the affected ASN.

**Response**

Disable Cloudflare auto-selection for affected users. Switch to whitelist-mode guidance: direct users to Telegram MTProto proxy or a whitelisted relay path. Record the affected ASNs and date range in `ops/live-infra/`.

---

## When to Disable Cloudflare Auto-Selection

Disable Cloudflare path in auto-selection when **any** of the following conditions hold:

1. Class 1 (EdgeThrottling): throughput on the Cloudflare path is consistently below 30% of the non-Cloudflare baseline for ≥15 min.
2. Class 2 (DomainBlocked): the primary and at least one fallback hostname are both blocked.
3. Class 5 (WhitelistShutdown): confirmed via two distinct Cloudflare prefixes from the affected ASN.

Do **not** disable auto-selection for Class 3 (OriginFailure) or Class 4 (ClientProtocolFailure) — those are fixable without demoting the Cloudflare path.

---

## Sensitive Findings Storage

Live hostnames, IPs, per-server health observations, credentials, and rotation history must be stored in the `censorship-bypass` vault under `ops/live-infra/`. Never embed them in this runbook or in source code.

Reference the relevant `ops/live-infra/` entry by filename in your incident notes.

---

## Related

- `CloudflareDegradationClass` enum: `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/CloudflareDegradationClass.kt`
- Epic: Remove Cloudflare from critical path
- Task: Add Cloudflare large-payload healthcheck
