## Context

The preceding six fixes made stream handling and unsupported import rejection explicit. The current JSON importer still reads vnext and publicKey only. Capability predicates are repeated across runtime-state, service, and editor. Existing outbound interop infrastructure already builds pinned Go peers without changing upstream dependency manifests.

## Goals / Non-Goals

- Goal: add three separately committed improvements with behavioral regression tests.
- Non-goal: implement packet-up, ML-DSA, VLESS Encryption, new backend routing, or protocol changes.

## Decisions

- Extend scripts/tests/run-outbound-interop.py and its CI dispatcher with an Xray oracle pinned to 0afec66085a85191e9dd2a4a6646c7c9a94688d1. Use only ephemeral loopback listeners and owned echo targets. No external decoy traffic is needed.
- Parse flat and legacy VLESS inputs into existing profile fields. Accept equal aliases; reject conflicting key aliases and mixed settings shapes. Keep unknown options fail-closed.
- Preserve the existing persisted model. Place the shared capability policy in settings, which runtime-state already consumes. This keeps dependency direction unchanged. Keep Rust runtime validation as an independent trust boundary and verify agreement through behavioral tests.
- Automatic subscription refresh is atomic with respect to input rejection: retain old group members when any requested node cannot be represented. Error output contains field names and reason codes, not values.

## Contracts and ownership

- Main: primary agent alone writes capability integration, task records, and commits/pushes. User explicitly requests main commits; this overrides the normal writer worktree rule for the primary agent.
- Isolated interop writer: native/rust/crates/ripdpi-vless/tests/upstream_interop.rs, scripts/tests/outbound-vless-oracle/, scripts/tests/run-outbound-interop.py, scripts/ci/run-outbound-interop.py, their owning Python tests, existing interop documentation and workflow step label if needed.
- Isolated import writer: runtime-state NativeVlessImportOptions.kt and XrayConfigImportParser.kt plus parser tests; core/data subscription refresh and owning tests. Prepare a separate patch for the modern import and preservation improvement.
- Primary agent is the only writer for capabilities and their UI/service/settings callers after the importer patch is applied. Serialized registries, schemas, Cargo.lock, and baselines remain unchanged. The primary agent owns one new flow label across all ten locale sets.
- No JNI, protobuf, wire, or storage migration. Unknown native configuration input remains rejected. Generic URI, SingBox, and Clash records remain available for lossless preview; native refresh checks the common capability policy before persistence.

## Risks / Trade-offs

- A REALITY decoy must meet upstream TLS behavior: prove it with the real pinned peer, not a stub handshake.
- Existing invalid saved combinations fail earlier: retain their fields, show validation errors, and never coerce to a supported profile.
- CI runtime increases: reuse the existing dispatcher and bound each peer/test lifetime; fail on zero selected tests.
- Gate commands: pinned vless interop runner; guarded cargo test --locked -p ripdpi-vless; affected Kotlin module tests; full staticAnalysis and architecture checks; independent general and async review.

## Editor integration

The editor now exposes an explicit flow choice from the same policy. It retains the saved flow until the user selects another value; choosing a transport does not erase it. A nullable draft-only override writes the existing RelayProfileRecord flow field, with no settings schema change. One label is translated in all ten locales. The existing RipDpiChip and theme tokens provide the control. The RDS vpn-relay-picker reference does not specify a flow control; this small functional addition is covered by the existing readability matrix at narrow widths and large font scales.

## Migration Plan

No data migration. Supported legacy input continues to work. Rejected input leaves prior durable state intact. Revert each scoped implementation commit if needed. Hosted CI, device, and release evidence remain separate from local validation.
