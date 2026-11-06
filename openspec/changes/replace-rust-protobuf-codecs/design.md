## Context

Task RST-1793945484896628 replaces two custom Protobuf codecs. GeoSite has an existing Kotlin-owned schema. Hunk contains one bytes field and uses a five-byte gRPC envelope. The current transport hides decode errors as EOF.

## Goals / Non-Goals

- Goal: Use standard message semantics and explicit bounded framing errors.
- Non-goal: Add compressed gRPC support, change HTTP/2 trailers, replace the relay runtime, or alter the sing-box database format.

## Decisions

- Use prost 0.14.4 for both codecs. Generate GeoSite Rust types from core/data/model/src/main/proto/geosite.proto with prost-build 0.14.4. Use protoc-bin-vendored 3.3.0 as a host build dependency so builds need no system protoc. These packages are build/runtime dependencies for the approved codec replacement; prost is Apache-2.0, the protoc wrapper is MIT. Check transitive licenses and advisories with cargo deny.
- Track the shared GeoSite schema in every Gradle native Cargo task so schema edits invalidate cached native outputs.
- Keep the generated GeoSite types private. Dispatch sing-box only for its version-zero prefix, since Protobuf field order is not fixed. Reject unknown domain enum values instead of interpreting them as broad Plain matches.
- Keep a small sing-box cursor for its own layout. Use the library varint decoder and checked lengths; remove Protobuf field parsing from it.
- Derive the Hunk message codec with a Bytes data field. Keep the bounded gRPC envelope and update encoding to return Result. Decode from immutable Bytes to retain shared payload storage.
- Propagate decode and incomplete-frame errors through a one-shot error channel before closing the reader stream. Keep all complete earlier payloads readable. Empty Hunk messages carry no stream bytes and do not end the stream.

## Contracts and ownership

- One writer owns ripdpi-geo, ripdpi-xhttp, Cargo.toml, Cargo.lock, and task artifacts. Review agents have read-only access.
- The existing GeoSite schema does not change. No Kotlin, JNI, persisted settings, or network socket ownership changes.
- Rust parser error variants and Hunk encoding signatures change; update every repository caller. No compatibility wrappers.

## Risks / Trade-offs

- New codec behavior accepts unknown fields but rejects malformed encodings; test independent wire vectors rather than only round trips.
- Code generation adds host build dependencies; lock versions and verify dependency policy.
- Stream failure propagation touches async code; require independent cancel-safety review.
- Generated APIs must stay private; source/API gates and affected consumer compilation check the boundary.

## Migration Plan

Add failing regression tests, replace both codecs, then run build-gate -- cargo test -p ripdpi-geo -p ripdpi-xhttp --locked and affected-crate clippy. Run cargo deny --locked check, metadata --locked, and build-gate -- just ci-gates. Review before commit. Rebase onto origin/main, rerun affected checks if inputs change, fast-forward main, and push. Observe hosted CI. A revert restores the former implementation without a stored-data migration.
