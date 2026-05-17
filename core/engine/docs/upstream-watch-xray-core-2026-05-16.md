# Upstream watch: xray-core REALITY / ECH / XHTTP

**Owner:** Senior Control-Plane Engineer (rotating; one per release cycle) **Cadence:** weekly skim + per-release deep-read **Trigger:** every xray-core tag, any commit touching `proxy/vless`, `transport/internet/reality`, `transport/internet/xhttp`, or `transport/internet/grpc` paths. **Owner channel:** post triage notes into the project's control-plane thread within 48 h of a new tag.

---

## Why this watch exists

xray-core moves fast and ships breaking schema/wire changes without SemVer signalling. Recent observed cadence:

| Tag | Date (UTC) | Breaking surface for RIPDPI |
|---|---|---|
| `v1.260206.0` | 2026-02-06 | REALITY SNI normalization change — `dest:port` validation tightened |
| `v1.26.1.18`  | 2026-04-18 | XHTTP + REALITY combination broken when `flow=""`; required `flow="xtls-rprx-vision"` |
| `v1.26.0601`  | 2026-06-01 *(announced)* | VLESS without `flow` deprecation; `allowInsecure` auto-disable |

A silent control-plane breakage here sinks every direct-relay client on the next host-pack publish. Catching it before publish is cheapest.

---

## Sources to read weekly

In priority order — the first three are mandatory each cycle:

1. **GitHub releases** — `https://github.com/XTLS/Xray-core/releases`. Read the body of every release tagged since the last watch entry.
2. **CHANGELOG-style commit log** — `https://github.com/XTLS/Xray-core/commits/main` filtered to the four path globs above.
3. **Pinned discussion tracker** — `https://github.com/XTLS/Xray-core/discussions` filter `is:pinned`. Schema-drift announcements land here first.
4. **Project Discord/Telegram** (optional) — pre-release signals when posted.
5. **Downstream tracker: sing-box** — `https://github.com/SagerNet/sing-box/releases`. sing-box pins xray-core compatibility ranges and often spots breakages before our own host-pack publish loop.

Each watch cycle the owner records (in this file's appendix):

- The xray-core tag range read.
- A one-line "no-change | additive | breaking" verdict per surface (REALITY, ECH, XHTTP, VLESS flow).
- A link to any host-pack validator update PR opened in response.

---

## Decision rules for the validator follow-up

The validator that rejects deprecated configurations lives in `core/data/catalog/XrayConfigValidator.kt` (outside this task's scope — tracked separately). When this watch surfaces a breakage, the owner files a follow-up issue with one of the standard rejection shapes:

- **Hard reject** — pre-publish validator fails the host-pack build if any client config matches the deprecated pattern. Example: `flow == ""` for `vless` after 2026-06-01.
- **Warn-only** — surfaces a publish-time warning; rolls to hard reject after one release cycle of advance notice. Example: when a field is officially deprecated but still works.
- **Compat shim** — emits a fixup in the publish pipeline (e.g. rewriting `allowInsecure: true` → `allowInsecure: false`). Use sparingly; document the shim in the validator and add an end-date.

The validator-update PR is the canonical record of which rule applies to each upstream change.

---

## Review interval and escalation

- **Default**: one watch entry per upstream tag, owner rotates per release cycle.
- **Escalation**: a "breaking" verdict opens a `priority: high` issue immediately and pages the control-plane owner; the next host-pack publish is held until the validator update lands.

---

## Watch log (append-only)

| Date | Tag range read | REALITY | ECH | XHTTP | VLESS flow | Validator PR |
|---|---|---|---|---|---|---|
| 2026-05-16 | initial entry | no-change | additive | breaking-known (`v1.26.1.18`) | breaking-known (`v1.26.0601` announced) | — |

---

## Links

- `Epic - Control-plane hardening`
- `Sign host-pack manifests with app-trusted keys`
- `Add anti-rollback to strategy-pack updates`
- `ripdpi-android-research-2026-04-20` §Upstream transport engines
