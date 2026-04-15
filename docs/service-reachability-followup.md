# service_reachability bootstrap — follow-up investigation

## Observed

On a clean (uncensored) network, `service_reachability` reports:

```
WhatsApp  -> service_blocked (bootstrap=http_unreachable, media=not_run, gateway=tls_ok)
Instagram -> service_blocked (bootstrap=http_unreachable, media=not_run, gateway=tls_ok)
Discord   -> service_blocked (bootstrap=http_unreachable, media=not_run, gateway=tls_ok)
```

`gateway=tls_ok` proves TLS reaches those providers. The failure is in the
HTTP bootstrap step, not trust store / TLS.

## Why this is NOT a trust-store issue

The DoT fix in task 9 seeds `ripdpi-tls-profiles` (BoringSSL) with the
Mozilla CA bundle. But `probe_http_url`
(`ripdpi-monitor/src/connectivity/endpoint.rs::probe_http_url`) routes
HTTPS bootstraps through `ripdpi-monitor/src/tls.rs`, which uses rustls
with `default_root_store()` (`tls.rs` L600-603) populated from
`webpki_roots::TLS_SERVER_ROOTS`. That path was already fine.

## Likely root cause — HTTP/1.1 bootstrap request is minimal

`http.rs::execute_http_request_targets` sends:

```http
GET / HTTP/1.1
Host: <target>
Accept: */*
Connection: close

```

No User-Agent, no Accept-Encoding, no Accept-Language. Modern
WAF-fronted origins (Cloudflare in front of Discord; Meta's edge in
front of WhatsApp / Instagram) increasingly reject requests lacking a
plausible User-Agent — either by closing the connection mid-response
(which looks like `http_unreachable` here) or by returning HTTP 403.

Secondary possibility: Instagram / WhatsApp may require HTTP/2 for
edge endpoints; plain HTTP/1.1 can be rejected at the TLS ALPN layer
or post-handshake.

## Proposed follow-up work (NOT in this change)

1. Add a plausible User-Agent to `execute_http_request*` so origins
   treat probe traffic as a well-formed client. A mild desktop Chrome
   UA is defensive enough without mimicking aggressively.
2. Confirm on emulator: after task 9 lands, run a full diagnostics
   scan on a clean network and capture:
   - Exact `error` field for `bootstrap=http_unreachable`.
   - Verify whether an HTTP 403/400/empty-response is returned.
3. If services still require HTTP/2, consider routing bootstrap
   probes through the reqwest DoH client path (which already ALPN-
   negotiates `h2` + `http/1.1`).

## Why this is gated on a live run

Without an emulator on a clean network we can't distinguish between:
- Connection reset after minimal HTTP/1.1 request (fix with UA).
- HTTPS reaching the origin but the origin returning an unexpected
  response shape (fix depends on that shape).
- Carrier/ISP-level tampering we're on a "clean" network we think.

The task is not actionable as a pure code change; it needs one scan
on hardware and then the follow-up PR based on what the probe saw.
