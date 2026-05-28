# ripdpi-apps-script-core

**Responsibility:** the Google Apps Script relay path — a domain-fronted proxy
that tunnels traffic through user-supplied Apps Script endpoints, with a SOCKS5
front and the MITM origin handling.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Selected by `relay_kind = "google_apps_script"`. Configured by the
`relay_apps_script_*` settings (script ids, Google IP, front domain, SNI /
direct host lists). The domain-fronting and Apps Script request format are the
interop contract.

## Dependency direction

**Upstream:** `ripdpi-tls-profiles` (`tokio`, `rustls`). **Downstream:**
`ripdpi-relay-android` → `libripdpi-relay.so`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add Apps Script proxy / domain-fronting behavior behind the existing config.
2. User data must never leave the device except through the user-configured
   Apps Script endpoints — no telemetry of script ids or front domains.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
