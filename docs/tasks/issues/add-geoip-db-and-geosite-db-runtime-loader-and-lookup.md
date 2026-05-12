---
title: Add geoip.db and geosite.db runtime loader and lookup
type: task
status: doing
area: routing
priority: high
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-12
---

- [ ] #task Add geoip.db and geosite.db runtime loader and lookup #repo/RIPDPI #area/routing #status/doing ⏫

## Summary

Load `geoip.db` (MaxMind format) and `geosite.db` (SagerNet sing-geosite
binary) at service start, expose lookup APIs to the Rust rule matcher
for `geoip:<code>` and `geosite:<category>` rule entries.

## Context

The geosite Protobuf schema already exists; this task is the runtime:
memory-mapped load, indexed lookup, version-tracked. Files live in
external files dir and can be replaced by the asset-provider task
without reboot (reload signal).

## Acceptance criteria

- [ ] `ripdpi-geo` crate opens `geoip.db` via `maxminddb-golang`
    equivalent Rust crate and `geosite.db` via a native parser.
- [ ] Lookup is O(log n) or better for geoip (CIDR lookup), O(1)
    amortized for geosite (category pre-compiled).
- [ ] Memory-map both DBs; on reload, atomically swap the mapping so
    in-flight lookups see either the old or new version consistently.
- [ ] JNI exposes the version string of each loaded DB for UI
    surfacing.
- [ ] Missing DB files log a typed warning and disable geo rules
    cleanly; no runtime panic.
- [ ] Unit tests cover: valid lookup, missing category, missing
    geoip file, version read.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`) — the loader lives in the Go `libcore` module, not the app:

- `libcore/geoip.go` — opens `geoip.db` (MaxMind format), iterates all networks, filters by country code, produces `[]option.HeadlessRule`. Uses `github.com/oschwald/maxminddb-golang`.
- `libcore/geosite.go` — opens `geosite.db` (sing-box native binary), reads a category, compiles domain/suffix/keyword/regex sets.
- `libcore/assets_android.go` — APK-asset-extraction path (pull `geoip.dat`/`geosite.dat` from assets on first run).

**Rust port path:**
- Use the [`maxminddb`](https://crates.io/crates/maxminddb) crate (Rust MaxMind DB reader) for geoip.
- Geosite binary format: port the sing-box Go reader (~200 lines) — no Rust crate exists. The format is a simple length-prefixed series of categories + domain entries with type tags (PLAIN/REGEX/DOMAIN/FULL).

**Adapt:** Both file formats end-to-end, category lookup API, memory-map + atomic-swap pattern (for reload without restart). **Skip:** Go implementation's headless-rule output (RIPDPI's rule matcher has its own match API).

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Add Rust rule matcher with domain ip port process matchers]]

## Work log

### 2026-05-12

- Added the native `ripdpi-geo` crate as the runtime boundary for mapped `geoip.db` and `geosite.db` files.
- Added mmap-backed file loading, typed missing-file warnings, atomic reload via `ArcSwap`, version reporting for loaded files, and a parser for the existing `GeositeCatalog` protobuf schema.
- Added unit coverage for valid geosite lookup, missing database files, version reporting, and reload swapping.
- Verified with `cargo test -p ripdpi-geo --locked` and `cargo clippy -p ripdpi-geo --all-targets --locked -- -D warnings`.
- Remaining before close: add MaxMind `geoip.db` country lookup, expose versions/lookups through JNI, and integrate `geoip:<code>` / `geosite:<category>` into the Rust rule matcher.
