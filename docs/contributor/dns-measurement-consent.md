# DNS measurement consent — user-requested destinations only

RIPDPI follows the C-Saw **"measurement with consent"** posture for the
encrypted-DNS / HTTPS-SVCB classifier: **DNS measurement happens only for the
destination the user is actually trying to reach.** There is no preloaded
destination catalog, and no background DNS scanning sweep.

This document is the recorded review for the encrypted-DNS / HTTPS-SVCB
classifier's measurement-with-consent posture. Read it before adding any new
DNS probe, resolver survey, or background task that issues DNS queries.

## The rule

1. **No preloaded destination list on the measurement path.** A DNS
   measurement (classification, integrity check, resolver survey, ECH/HTTPS-RR
   query) must take its target host from a live request — a `ScanRequest`
   target the user selected, or the host of an actual flow the user opened.
   It must never iterate a hardcoded array of popular domains.
2. **Measurement is always tied to a live flow / explicit scan.** If you cannot
   point at the user action that produced the target host, the measurement is
   not allowed to run.
3. **Coarse keys only, if results are ever uploaded.** RIPDPI has no backend
   today (see `AGENTS.md` § Project Rules). If a future opt-in shared-priors
   upload lands, the uploaded record carries only coarse keys — never raw user
   URLs, SSIDs, or precise geolocation. See
   [`.claude/rules/network-fingerprint-privacy.md`] for the forbidden-input
   list and the scope-hash recipe.

## Where measurement is scoped (enforcement points)

| Layer | Source of the target host |
|---|---|
| Kotlin scan launch | `DiagnosticsScanRequestFactory` projects targets from the user-selected profile / cohort into a `ScanRequest`; nothing else feeds targets. |
| Rust DNS probes | `ProbeContext` carries an opaque `network_scope_key` and a `resolver_hint` — **never a target list**. The DoH survey runners (`doh_survey`, `doh_json_survey`) query exactly their single `query_host`, contacting only the configured *resolver endpoints*. |

The regression guard
`native/rust/crates/ripdpi-diagnostics-probes/tests/dns_measurement_consent.rs`
drives the survey runners with a recording HTTP client and asserts that every
request targets the supplied host and never a preloaded destination. Keep it
green; if a new measurement runner is added, extend the guard to cover it.

## What is NOT DNS measurement: connectivity warmup

Two preloaded domain catalogs exist in the **runtime data plane**, and they are
deliberately *out of scope* of this rule because they are connectivity warmup,
not measurement or classification:

- `ripdpi-proxy-runtime` `runtime/warmup/target_catalog.rs` — `PROBE_DOMAINS`,
  probed once at startup to pre-establish routes.
- `ripdpi-runtime-services` `background_probes_impl.rs` — `REPROBE_DOMAINS`,
  re-warmed when the network *identity* changes (transport / SSID hash /
  operator / DNS servers), gated on a validated, non-captive network.

These open connections through the normal routing pipeline so the proxy is warm
when the user's first real request arrives; they do **not** feed the DNS
integrity classifier, and their results are never persisted as DNS verdicts.

**Hard boundary:** never repurpose a warmup catalog as a measurement target
source, and never grow it into a "periodic DNS health sweep". Warmup probing a
fixed set of popular hosts on network join is a deliberate, reviewed
performance trade-off; turning it into background DNS *measurement* against a
preloaded list is exactly the C-Saw violation this rule forbids. If a warmup
catalog ever needs to change role, that is a product decision, not a quiet
refactor.

## Checklist for new DNS-touching code

- [ ] The target host comes from a live flow or an explicit user scan request.
- [ ] No `&[&str]` (or asset-bundled) destination list is iterated to issue DNS
      queries for measurement.
- [ ] Any new measurement runner is covered by the consent guard test.
- [ ] No raw user URL / SSID / device IP is logged or persisted; only the
      SHA-256 scope hash and coarse keys leave the measurement code.

## Links

- `.claude/rules/network-fingerprint-privacy.md` — scope-hash recipe and
  forbidden identifiers.
- `native/rust/crates/ripdpi-diagnostics-probes/tests/dns_measurement_consent.rs`
  — the regression guard for this rule.
