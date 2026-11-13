## Context

The changes repair existing contracts identified by the DPI-11 audit. Each defect has a separate implementation commit.

## Goals / Non-Goals

- Goal: enforce the current native input, envelope, arithmetic, and saved size contracts.
- Non-goal: expand multi-record scope, change RNG distribution, or change default applicability.

## Decisions

- Validate normalized native fields in the shared converter used by primary and rotation chains.
- Reuse is_tls_client_hello at the normalization boundary. Keep the tolerant packet parser and intentional minor-version override.
- Saturate intermediate size products, including helper feasibility products. Keep the direct model API shape.
- Clamp adaptive adjustments to the saved interval. User size bounds are hard limits, as stated in the UI.
- Use existing owning test suites and independent output properties. Do not add dependencies or production test seams.

## Contracts and ownership

- config_bounds owns ripdpi-proxy-config converter and its tests.
- tls_envelope owns ripdpi-desync first_flight_ir.rs, the tlsminor guard in apply_prelude, and existing envelope tests.
- runtime_bounds owns ripdpi-desync offset.rs, only resolve_tlsrandrec_fragment_sizes in tls_prelude.rs, and arithmetic/adaptive tests.
- dpi11-audit exclusively owns task/OpenSpec/board files and integration.
- No serialized schema, baseline, locale, or lockfile changes.

## Risks / Trade-offs

- Native clients with invalid positive values receive a configuration error.
- Clamped profiles can safely skip layouts that do not fit. Tests must check the saved interval and payload preservation.
- Full Android transport and external-peer acceptance are outside this regression scope; local source and owning tests establish the repaired contracts.

## Migration Plan

No storage or wire migration. Integrate each reviewed fix with fast-forward Git history. Rollback uses normal revert commits if required.
