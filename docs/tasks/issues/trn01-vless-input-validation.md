---
id: TRN-1794335331879799
title: Reject malformed VLESS addons and unsupported origin flows
kind: bug
status: review
area: transport
priority: high
owner: Native transport maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: trn01-vless-input-validation
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Reject malformed VLESS protobuf addons before payload delivery. Reject unsupported wire flows in the Cloudflare origin before authentication completion or target connection. Document the eager response reader cancellation contract.

## Acceptance criteria

- Request and all response decoders reject the five audited malformed classes and accept valid unknown protobuf fields.
- Lazy response reads preserve state across every tested split and cancellation.
- Origin rejects all nonempty wire flows before authentication callbacks, target accepts, or success replies; empty flow still relays bytes.
- Existing codec, flow-policy, origin, and affected-consumer tests pass with locked dependencies.
- Independent general and async reviews have no unresolved findings; CI source gates pass.
- Each correction has a separate commit and is integrated into main and pushed, as requested.

## Ownership and serialized lanes

- Decoder worker: /private/tmp/ripdpi-trn01-decoder; ripdpi-vless/src/addons/**, addons.rs, wire.rs and wire/**. This worker exclusively owns wire.rs until F1 is committed.
- Origin worker: /private/tmp/ripdpi-trn01-origin; ripdpi-cloudflare-origin/src/session.rs and session/**. Depends on decoded flow: String from F1.
- Integrator: /private/tmp/ripdpi-trn01-input; portfolio and OpenSpec artifacts, combined validation, commits and main integration. The decoder worker includes the eager-helper cancellation documentation in F1.
- Read-only reviewers: combined diff; no source writes.
- Cargo.lock, dependency manifests, schema versions, golden fixtures, baselines, locales and upstream pins have no assigned writer because no change is planned.
- Planning artifacts must pass strict validation before implementation. No new production dependency, new transport mode, physical probe, external-server acceptance or credential use is included.
