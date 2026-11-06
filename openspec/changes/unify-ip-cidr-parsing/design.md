## Context

The audit found independent address grammars in Kotlin and Rust. Some accept invalid IPv6 or silently replace malformed CIDR prefixes. See proposal.md and REQ-IP-LITERAL, REQ-IP-CIDR, and REQ-IP-WIRE.

## Goals / Non-Goals

- Goal: replace manual grammar and subnet arithmetic with maintained libraries and a small strict-input policy.
- Goal: reject malformed input without DNS and preserve explicit domain restrictions and canonical routing bytes.
- Non-goal: change DNS transport, VPN lifecycle, network fingerprint recipes, or packet codecs.

## Decisions

- Kotlin uses IPAddress 5.6.2 (Apache-2.0, Java 8, Android API 24+) in core:data:model. Strict options reject ranges, wildcards, zones, legacy IPv4, binary/base85 forms, and implicit trimming. The library owns grammar, family, masking, and containment.
- Rust uses the already locked ipnet 2.12.1 (MIT/Apache-2.0) for network types and membership. Parse address text through std::net::IpAddr before IpNet::new: ipnet's string parser alone permits leading-zero IPv4 octets.
- Prefixes are unsigned ASCII decimal without leading zeros, except the single digit zero. Bare-address callers may supply their existing explicit default; malformed explicit CIDRs never use defaults.
- Interface hosts retain host bits. Routing uses network addresses. Mapped IPv6 retains family in general parsing; profile-specific exclusions remain explicit.
- Preserve expanded lowercase IPv6 routing text and existing storage/wire schemas. No generic DNS resolver is used for numeric validation.

## Contracts and ownership

- Root owns Kotlin shared parsing, Gradle versions/dependencies, planning, final validation, and integration.
- Kotlin caller worker owns app/core call sites and their tests in a separate worktree, excluding core:data:model and build files.
- Rust worker owns native/rust source, crate manifests, Cargo.toml, and Cargo.lock in a separate worktree. This is the single writer for the Rust dependency lane.
- All writers preserve unrelated work. No protobuf/JNI field or golden fixture changes are planned.

## Risks / Trade-offs

- Library defaults are permissive: enforce strict options and boundary regression cases.
- IPv4-mapped IPv6 can collapse in java.net: retain the library family and construct Inet6Address where needed.
- CIDR changes affect routing: preserve canonical fixtures and verify zero/full-width prefixes and cross-family rejection.
- Dependencies add maintenance and artifact size: use the existing locked Rust version and one JVM dependency without additional runtime services.

## Migration Plan

Reject malformed configuration instead of keeping compatibility paths. Existing valid stored configurations retain their representation. Run affected Kotlin unit tests, Rust package tests, staticAnalysis, task validation, and just ci-gates. Require an independent pr-reviewer pass before commit. Rebase on origin/main, repeat affected gates for new inputs, merge with --ff-only, push, and inspect hosted CI. No device or deployment action is needed for pure parsing; device transport and APK-size changes remain unmeasured.
