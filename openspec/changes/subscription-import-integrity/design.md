## Context

The audit follows each parser through refresh, bootstrap, storage, and explicit activation. Native backends support a smaller set than the external formats.

## Goals / Non-Goals

- Goal: prevent silent semantic loss and partial replacement, and retain an initial selector preference.
- Non-goal: implement detour chains, new TLS wrappers, arbitrary upstream routing, or full config export.

## Decisions

- Reject an unsupported wire or trust option instead of removing it. Keep harmless display metadata separate from connection constraints.
- Treat malformed nodes as parse errors. Apply native validation before both ordinary and bootstrap persistence.
- Keep single-use delivery outcome separate from whether its payload can be activated. A rejected body must not cause an unsafe replay policy.
- Save resolved selector candidates and imported default through one identifier mapping. Imported default is an initial preference only; an explicit selection wins. Import never starts VPN.
- Follow the current Xray pin for flat credentials, and reject unsupported stream security.
- Apply the existing 2 MiB and 2048-node import budgets to manual Xray validation. Run parsing outside Main and bind results to the current input/provider generation. Reuse existing dispatcher conventions and expose a localized size failure without truncating credentials.
- Decode payloads with a reporting charset decoder. Do not replace malformed bytes in credentials.
- Replace a subscription-owned failover policy when a refreshed sing-box selector explicitly declares manual selection. Preserve policy for plain/Base64 inputs that make no declaration.
- Use documented URL-test cadence and tolerance defaults. Require an explicit valid probe URL, and reject omitted URLs instead of introducing an implicit external probe host. Validate bounded durations and allowed HTTP URLs. No external probe is needed for tests.

## Contracts and ownership

- Modules: :core:data:runtime-state owns parser and domain output; :core:data owns durable mutations; :app owns dispatch and confirmation; :core:service owns explicit runtime resolution.
- Parser writer owns route, Clash, and sing-box mapping. Xray writer owns native Xray translation. Selector writer owns bootstrap, selector policies, and optional saved default. Integrator owns payload decoding and planning artifacts.
- Each writer uses an isolated worktree. Files shared by findings in one lane are serialized by that writer. Dependency versions, locales, protobuf, JNI, golden fixtures, and Rust registries do not change.
- An optional saved default is additive with an empty/null default. Old groups continue to load. No generated schema or output is edited by hand.

## Risks / Trade-offs

- Strict admission can reject previously accepted configs. Tests must prove rejection leaves stored working data intact.
- Bootstrap tokens may already be consumed when a body fails validation. Preserve a terminal delivery receipt without saving invalid profiles.
- Initial default must not bypass manual activation authority or replace user choice.
- Default URL-test probes are a declared upstream behavior. Tests use local synthetic targets and never contact that URL.

## Migration Plan

Add only optional selector metadata where required. Existing groups and explicit choices retain their behavior. Rollback reads old groups; no destructive conversion occurs. Run targeted parser, Room, selector, and ViewModel tests; affected lint; just ci-gates; task checks; architecture health and locked Cargo metadata before integration. Required independent review must pass before behavior commits. Device/native transport compatibility is outside this parser-only acceptance; no such evidence is claimed.
