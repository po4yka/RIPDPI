## Context

The parser maps REALITY flow with a Vision default and has no multiplex field in ProxyProfile. See proposal.md.

## Goals / Non-Goals

- Goal: fail closed for unsupported enabled multiplex and preserve no-flow semantics.
- Non-goal: implement native smux, migrate existing stored profiles, or change URI and Clash defaults.

## Decisions

- Check enabled multiplex before profile mapping. Reuse UNSUPPORTED_TRANSPORT with the fixed detail multiplex, so no new UI or locale contract is needed.
- Map absent sing-box REALITY flow to empty, as the plain VLESS mapper already does. Explicit Vision remains unchanged.

## Contracts and ownership

- Only runtime-state parsing and core data JVM tests change. Mux import writer owns these files and the linked task artifacts in an isolated worktree.
- No Rust, JNI, protobuf, persistence schema, lockfile, locale, or golden fixture changes.
- Parent integration writer serializes generated board integration; AWG refresh work is isolated.

## Risks / Trade-offs

- Unsupported multiplex nodes become unavailable. This is safer than storing changed wire parameters; supported siblings remain available.
- Existing incorrectly imported profiles require subscription refresh; this change does not infer their original settings.

## Migration Plan

No storage migration. Revert the parser commit to roll back. Verify with SingBoxMultiplexImportTest, VlessRealityImportTest, SingBoxSubscriptionParserTest, FleetCompatGoldenFileTest, and RipdpiBundleContractTest through :core:data:testDebugUnitTest; run affected ktlint and detekt. No VPS or device acceptance is claimed.
