---
paths:
  - "app/**/*.kt"
  - "core/data/**/*.kt"
  - "core/diagnostics/**/*.kt"
  - "core/service/**/*.kt"
  - "native/rust/**/*.rs"
---

## Remembered-network fingerprint contract

`NetworkFingerprint.scopeKey()` in `core/data/model/.../NetworkStrategyMemory.kt` is the source of truth for the persisted remembered-network key. The current unversioned recipe normalizes each string, joins the canonical parts with `|`, and stores only the SHA-256 hex digest.

### Current canonical parts

Every key begins with transport, validation state, captive-portal state, private-DNS mode, and sorted normalized DNS servers. The identity suffix is:

- Wi-Fi: `wifi`, SSID, BSSID, and gateway.
- Cellular: `cellular`, operator code, SIM operator code, carrier ID, data-network type, and roaming state.
- Other transports: `other`, normalized transport, normalized DNS list, and private-DNS mode.

SSID, BSSID, gateway, operator fields, and DNS addresses are raw in-memory hash material in the current implementation. They must not be logged or persisted separately; only `scopeKey()` and the non-identifying `NetworkFingerprintSummary` may cross the remembered-policy persistence boundary. Do not describe the implementation as pre-hashing SSID or excluding these fields unless the code and migration contract change together.

### Compatibility and privacy

Changing field order, normalization, sentinel handling, or included fields changes every affected key and orphans existing remembered policies. Treat such a change as a versioned storage migration: update the implementation, tests, compatibility policy, and user-visible privacy documentation in one change. Do not silently replace the current recipe from this rule file.

Never add IMEI, IMSI, MEID, ESN, user-account identifiers, or raw identifiers to logs, telemetry, crash reports, goldens, or persisted records. Logs should use the scope hash or the redacted summary. Audit raw identifier collection and export behavior against the actual data flow; a hash alone does not justify categorical GDPR or Play Data Safety claims.

### Audit

```bash
rg -n "scopeKey|canonicalParts|WifiNetworkIdentityTuple|CellularNetworkIdentityTuple" core/data --type kotlin
rg -n -i "imei|imsi|meid|bssid|ssid|operatorCode|carrierId" app core native/rust
```

Classify hits by whether they remain transient hash inputs, are reduced to `NetworkFingerprintSummary`, or escape into persistence/logging. Only the last category is a privacy defect.

### Cross-references

- `rust-observability` skill for telemetry channel selection and redaction.
- `android-vpn-lifecycle.md` for remembered state lifecycle.
- `llm-rust-prompts.md` for raw-identifier review sentinels.
