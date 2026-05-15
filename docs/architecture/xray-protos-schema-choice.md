# `xray-protos` Schema Choice — ADR

> Status: **draft; awaiting code implementation**.
> Authored: 2026-05-15.
> Tracking task: `docs/tasks/issues/populate-xray-protos-crate-with-config-schema.md`.

## Question

The `xray-protos/` crate is a stub. The `epic-xray-provider-mode` work
depends on a parsed Xray client-config representation. Should that
representation come from:

- **Option A — vendored `.proto` files** from xray-core compiled by
  `prost-build`, or
- **Option B — a hand-rolled `serde_json` schema** covering only the
  surfaces RIPDPI exposes?

## Decision

**Option B — hand-rolled `serde_json` schema**, scoped to the Xray
config shapes that the in-app profile editor and the host-pack
publisher actually emit:

- VLESS + REALITY outbounds
- XHTTP transport
- Routing-rule subset (domain, IP, port matchers)
- Inbound listeners (SOCKS/HTTP, when used)

Validation (e.g. reject VLESS-without-flow after 2026-06-01) is built
on top of the typed struct, not on raw JSON.

## Rationale

- **Build-toolchain cost.** Option A pulls a `protoc` requirement
  into every developer's build and CI runner. xray-core's proto set
  is large; we would compile far more than we use.
- **Drift control.** Option B forces an explicit decision every time
  RIPDPI consumes a new Xray config surface, which is the level of
  signal we want for an upstream-tracking task. Option A would
  silently accept new fields.
- **Validation ergonomics.** Hand-rolled Rust types let validation
  rules live next to the type definitions. Generated prost types
  require either a separate wrapper or `serde` impl crate.
- **Scope match.** The in-app editor only writes ~5% of Xray's
  config surface; full prost coverage is wasted code.

## Trade-offs accepted

- **Manual sync cost.** Xray adds fields; we add them manually as
  needed. This is acceptable given the upstream-watch job
  (`add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`)
  surfaces drift.
- **No automatic backward-compat shim.** New Xray fields are dropped
  on parse unless we model them. `serde(deny_unknown_fields)` is
  enabled to make this explicit.
- **Re-evaluation trigger.** If we ever need to render a *full*
  arbitrary Xray config (e.g. import-from-external-source), revisit
  this decision and consider a layered approach: hand-rolled for
  edit, prost-generated for round-trip.

## Implementation outline

1. `xray-protos/Cargo.toml` adds `serde`, `serde_json`, `thiserror`.
2. `xray-protos/src/lib.rs` exposes:
   - `pub struct XrayConfig { outbounds: Vec<Outbound>, inbounds: Vec<Inbound>, routing: Routing }`
   - `Outbound` enum: `Vless { /* + flow, transport */ }`, future variants.
   - `Transport` enum: `Xhttp { /* */ }`, future variants.
   - `pub fn parse(json: &str) -> Result<XrayConfig, ParseError>`
   - `pub fn serialize(cfg: &XrayConfig) -> Result<String, SerError>`
   - `pub fn validate(cfg: &XrayConfig, ctx: &ValidationContext) -> Result<(), Vec<ValidationError>>`
3. Validation rules:
   - VLESS outbound without `flow` → error when context date >= 2026-06-01.
   - `allowInsecure: true` → error when context date >= 2026-06-01.
   - REALITY + XHTTP combination at upstream pin v26.1.18 → error.
4. `tests/fixtures/` carries `valid_*.json` and `invalid_*.json` cases.

## Owner

Engine / outbound owner picks up the implementation work as the
`populate-xray-protos-crate-with-config-schema` task.
