# Change: Unify strict IP and CIDR parsing

Task ID: `RTE-1793945596239402`

## Why

Independent Kotlin and Rust parsers disagree on numeric addresses and subnet boundaries. The Kotlin resolver accepts malformed IPv6 such as `:1::2`. Manual parsing also risks hostname resolution at validation boundaries.

## What Changes

- Use library parsers for numeric IPv4, IPv6, and CIDR values across configuration, routing, diagnostics, and profile validation.
- BREAKING: reject malformed literals, legacy IPv4 forms, scoped addresses, and invalid prefixes consistently. Keep explicit caller whitespace handling.
- BREAKING: replace Rust `Cidr` and `prefix_match_bytes` with `ipnet::IpNet`; update all repository callers and public API snapshots.
- Preserve host addresses in interface CIDRs and clear host bits for network rules. Preserve established canonical wire text and address-family isolation.

## Capabilities

### New Capabilities

- `strict-ip-cidr-parsing`: shared numeric-address syntax and subnet behavior.

### Modified Capabilities

- None.

## Impact

- Kotlin data model, profile settings, engine codecs, services, diagnostics, and UI validation.
- Rust configuration, routing, and diagnostic subnet matching.
- Use IPAddress on Kotlin and ipnet on Rust. No backend, schema version change, or socket lifecycle change.
