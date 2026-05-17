# `xray-protos` Schema Choice — ADR (REVISED)

> Status: **revised 2026-05-15 to reflect actual project state**. Authored: 2026-05-15. Tracking task: `docs/tasks/issues/populate-xray-protos-crate-with-config-schema.md`.

## Correction

This ADR was originally drafted assuming `xray-protos/` was a Rust crate stub. **It is not.** `xray-protos/` is a Gradle module (`build.gradle.kts` with the `com.google.protobuf` plugin) that already vendors 13 `.proto` files from xray-core and compiles them to Java (`lite` runtime) at build time.

Vendored proto sources under `xray-protos/src/main/proto/`:

- `core/config.proto`
- `transport/internet/config.proto`, `transport/internet/reality/config.proto`
- `app/proxyman/config.proto`, `app/proxyman/command/command.proto`
- `proxy/vless/account.proto`, `proxy/vless/outbound/config.proto`
- `common/net/address.proto`, `common/net/port.proto`
- `common/protocol/server_spec.proto`, `common/protocol/user.proto`
- `common/serial/typed_message.proto`

The schema choice was therefore made *before* this ADR was written.

## Decision (effective state)

**Option A — vendored `.proto` files compiled to Java lite.** This is the existing project state. The host-pack publisher and the in-app editor consume the generated Java types directly.

## Remaining work to close the task

Even though the schema mechanism is in place, the task's acceptance criteria still call for:

1. Round-trip parse/serialize tests for known-good Xray configs.
2. Validation that rejects deprecated combinations (VLESS-without-flow after 2026-06-01, REALITY+XHTTP at xray-core v26.1.18).
3. Positive and negative golden configs under `xray-protos/src/test/`.

These remain backlog items on the tracking task; the schema *plumbing* is no longer the blocker.

## Rationale

- **Build-toolchain cost.** Option A pulls a `protoc` requirement into every developer's build and CI runner. xray-core's proto set is large; we would compile far more than we use.
- **Drift control.** Option B forces an explicit decision every time RIPDPI consumes a new Xray config surface, which is the level of signal we want for an upstream-tracking task. Option A would silently accept new fields.
- **Validation ergonomics.** Hand-rolled Rust types let validation rules live next to the type definitions. Generated prost types require either a separate wrapper or `serde` impl crate.
- **Scope match.** The in-app editor only writes ~5% of Xray's config surface; full prost coverage is wasted code.

## Trade-offs accepted

- **Manual sync cost.** Xray adds fields; we add them manually as needed. This is acceptable given the upstream-watch job (`add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`) surfaces drift.
- **No automatic backward-compat shim.** New Xray fields are dropped on parse unless we model them. `serde(deny_unknown_fields)` is enabled to make this explicit.
- **Re-evaluation trigger.** If we ever need to render a *full* arbitrary Xray config (e.g. import-from-external-source), revisit this decision and consider a layered approach: hand-rolled for edit, prost-generated for round-trip.

## Implementation outline

1. `xray-protos/Cargo.toml` adds `serde`, `serde_json`, `thiserror`.
2. `xray-protos/src/lib.rs` exposes: - `pub struct XrayConfig { outbounds: Vec<Outbound>, inbounds: Vec<Inbound>, routing: Routing }` - `Outbound` enum: `Vless { /* + flow, transport */ }`, future variants. - `Transport` enum: `Xhttp { /* */ }`, future variants. - `pub fn parse(json: &str) -> Result<XrayConfig, ParseError>` - `pub fn serialize(cfg: &XrayConfig) -> Result<String, SerError>` - `pub fn validate(cfg: &XrayConfig, ctx: &ValidationContext) -> Result<(), Vec<ValidationError>>`
3. Validation rules: - VLESS outbound without `flow` → error when context date >= 2026-06-01. - `allowInsecure: true` → error when context date >= 2026-06-01. - REALITY + XHTTP combination at upstream pin v26.1.18 → error.
4. `tests/fixtures/` carries `valid_*.json` and `invalid_*.json` cases.

## Owner

Engine / outbound owner picks up the implementation work as the `populate-xray-protos-crate-with-config-schema` task.
