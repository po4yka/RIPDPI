## Network fingerprint privacy

RIPDPI uses a per-network policy cache keyed by a SHA-256 hash of network identity. The hash inputs MUST stay within strict privacy bounds — Play Store Data Safety, GDPR, and downstream user trust all hinge on this.

### Canonical hash recipe

```
network_scope_key = SHA-256(
    transport_kind         ||  // "wifi" | "cellular" | "ethernet" | "other"
    validation_state       ||  // "validated" | "captive" | "none"
    private_dns_mode       ||  // "off" | "opportunistic" | "strict:<host>"
    sorted_join(dns_servers, ",") ||  // ascending lexicographic, IPv4/IPv6
    network_identity              // BSSID for wifi, carrier_id_tuple for cellular
)
```

`network_identity` per transport:
- Wi-Fi: `BSSID` lowercased. If `ACCESS_FINE_LOCATION` was denied (Android 10+), `BSSID` is `02:00:00:00:00:00` (Android-provided sentinel) — do NOT include this sentinel in the hash; substitute the SSID hash instead, or skip the identity component entirely. A constant sentinel BSSID would collapse all consent-denied users into one scope key.
- Cellular: `SubscriptionInfo.getCarrierId()` (numeric, locale-stable, Android 10+) joined with `MCC` + `MNC`. NEVER use `CarrierName` — it's localized; same carrier in different locales produces different scope keys.
- Ethernet: interface MAC if available; otherwise skip identity.

### Forbidden inputs

The following MUST NEVER appear in the hash, in logs, in telemetry, in goldens, or in any persisted artifact under any encoding (plain, base64, hex):

- IMEI / IMSI / MEID / ESN — under any path, including derived values.
- IPv4 / IPv6 addresses of user devices (LAN or WAN — anything other than the canonical DNS server IPs allowed above).
- WiFi SSID (the user-visible network name) in plain form. If you must include it, hash it FIRST then include the hash.
- Raw BSSID strings in logs — only the contribution to the scope hash. Use `tracing::debug!(scope=%scope_hash, "matched policy")`, not `tracing::debug!(bssid=%bssid, ...)`.
- User account identifiers from any installed app.

### GDPR / Play Data Safety implications

If RIPDPI's telemetry / persistent stores contain only `SHA-256` hashes of network identity (and no raw identifiers), the Data Safety declaration does NOT need to list "Device identifiers." If any raw BSSID / IMEI / cellular numeric ID leaks into a stored artifact, the declaration becomes mandatory and the user-facing data-safety surface expands. Audit `host-autolearn-v2.json`, telemetry exports, and crash logs before each release.

### Two-level scope (recommended refactor)

A single-level scope key including DNS servers collapses to a different value when the user changes Private DNS mode in system settings. All per-host policy for that physical network is orphaned. Mitigation:

- `network_scope` = hash over `(transport, validation, network_identity)`. Stable across DNS changes.
- `dns_scope` = hash over `(private_dns_mode, sorted dns_servers)`. Captures the DNS substrate.
- Policy lookup is a partial-match join: prefer exact `(network_scope, dns_scope)` match; fall back to `network_scope`-only if no DNS-match exists.

This survives Private DNS toggles without losing per-host learning.

### Audit

`grep -rE 'imei|imsi|bssid|carrier_name' native/rust/ app/src/ --type rust --type kotlin | grep -v "// allow:"` should return only the intentional uses (the hashing helper itself, the network-callback parser). Any other hit is a privacy bug.

### Cross-references

- `rust-android-telemetry` skill — telemetry channel selection.
- `android-vpn-lifecycle.md` rule — per-network policy survives process death.
- `llm-rust-prompts.md` — sentinel pattern entry for forbidden identifiers in AI-generated logs.
