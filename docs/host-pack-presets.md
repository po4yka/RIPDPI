# Curated Host-Pack Presets

RIPDPI bundles three curated host-pack presets for the Advanced settings screen:

- `youtube`
- `telegram`
- `discord`

These presets are app-side convenience lists only. They rewrite the existing `hosts_mode`, `hosts_blacklist`, and `hosts_whitelist` settings and do not add any new engine behavior.

The app ships with a bundled snapshot and can also refresh those packs manually at runtime. Runtime refresh is opt-in, uses the canonical `raw.githubusercontent.com` release artifact, and only replaces the on-device cache after SHA-256 verification succeeds.

## Source Policy

The app stays offline at runtime. Bundled packs are generated explicitly through:

```bash
python3 scripts/sync_host_packs.py
```

Bundled source inputs:

- `runetfreedom/russia-blocked-geosite` `release/youtube.txt`
- `runetfreedom/russia-blocked-geosite` `release/discord.txt`
- `v2fly/domain-list-community` `data/telegram`

The sync script normalizes those upstream lists into `app/src/main/assets/host-packs/catalog.json` and records the upstream URL, ref, and latest commit SHA for traceability.

## Runtime Refresh

The Advanced settings screen can fetch a newer curated snapshot from:

- `https://raw.githubusercontent.com/runetfreedom/russia-blocked-geosite/release/geosite.dat`
- `https://raw.githubusercontent.com/runetfreedom/russia-blocked-geosite/release/geosite.dat.sha256sum`

Runtime refresh behavior:

- Retrofit downloads the published checksum first.
- RIPDPI streams `geosite.dat` to a temporary file while computing SHA-256 locally.
- The app refuses to replace the current curated packs unless the computed digest matches the published checksum exactly.
- Only safe `ROOT_DOMAIN` and `FULL` geosite rules are converted into host-list entries.
- `PLAIN` and `REGEX` rules are skipped because RIPDPI host filters only support plain host suffix matching.
- The verified result is cached in app-private storage and survives restarts.

## Normalization Rules

- Plain host tokens are kept after lowercase normalization.
- `domain:` and `full:` geosite entries are converted by stripping the prefix.
- Unsupported geosite rule kinds such as `keyword:` and `regexp:` are skipped.
- Duplicate hosts are removed case-insensitively while preserving first-seen order.

## Matching Caveat

RIPDPI host filtering is suffix-based. Because the current engine has no exact-host rule type, converted `full:` entries behave like the exact host plus matching subdomains.
