# `xray-protos` Schema Choice — ADR

> Status: **current as of 2026-05-28**. Authored: 2026-05-15, revised after the Xray provider task split.

## Decision

`xray-protos/` is a Gradle `java-library` module using the `com.google.protobuf` plugin. It vendors the Xray/V2Ray `.proto` files needed by the current Xray API scanner and generates Java lite + gRPC lite classes at build time.

This is not a Rust crate and does not expose a hand-written `XrayConfig` model.

## Current Sources

Vendored proto sources under `xray-protos/src/main/proto/`:

- `app/proxyman/config.proto`
- `app/proxyman/command/command.proto`
- `common/net/address.proto`
- `common/net/port.proto`
- `common/protocol/server_spec.proto`
- `common/protocol/user.proto`
- `common/serial/typed_message.proto`
- `core/config.proto`
- `proxy/vless/account.proto`
- `proxy/vless/outbound/config.proto`
- `transport/internet/config.proto`
- `transport/internet/reality/config.proto`

The live consumer is `:core:detection`: `XrayApiClient` imports the generated `com.xray.*` classes to inspect a local Xray API endpoint. Runtime Xray client-config rendering is tracked separately by `docs/tasks/issues/render-validated-xray-client-configs.md`.

## Validation Shape

Runtime config validation does not live in `xray-protos`. The current validator is `core/data/catalog/src/main/kotlin/com/poyka/ripdpi/data/XrayConfigValidator.kt`; it operates on `JsonObject` so config-generation work can validate raw Xray JSON without committing to a generated-protobuf DTO as the product model.

The validator currently covers:

- VLESS users missing `flow`.
- `tlsSettings.allowInsecure = true`.
- REALITY + XHTTP at broken xray-core tags.

## Rationale

- Generated Java lite classes are useful for local Xray API inspection because that API is protobuf/gRPC-shaped.
- Product profile rendering needs a smaller, secret-safe model than the full Xray schema; that work belongs in the Xray provider tasks, not in this schema module.
- Keeping validation on `JsonObject` lets RIPDPI reject unsafe or upstream-broken combinations before the full provider renderer lands.
