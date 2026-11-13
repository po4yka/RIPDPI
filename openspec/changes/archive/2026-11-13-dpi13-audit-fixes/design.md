## Context

Task RST-1794556971423909 addresses audit F1-F5. Native multidisorder uses one terminal run, at least two markers and at least three nonempty regions. Keep that scope and existing activation gates.

## Goals / Non-Goals

- Goal: prevent replay after possible genuine delivery and remove silent configuration loss.
- Goal: use the existing bounded helper bulk transport and repair send-queue discipline.
- Non-goal: add zapret features, a new backend, dependencies, protocol-version changes, physical-device activation or external traffic probes.

## Decisions

- Mark errors from the grouped platform call as possible payload commit. Keep validation errors before that call distinct. Preserve existing known progress where available; do not add a broad error hierarchy merely for this fix.
- Add command-specific bulk support metadata. A generic legacy bulk advertisement does not prove support for multidisorder. Apply payload, segment-count and contiguous full-coverage bounds on both sides before dispatch.
- The first declared multidisorder marker owns grouped flags and delay. Default fields on later markers inherit this set. A later non-default value must agree with that set, otherwise validation rejects it. Resolution, activation, marker sorting and empty regions must not select a new settings owner. This preserves valid first-marker-only configurations and rejects values that were silently ignored.
- Reuse the existing repair send-queue helper after reverse raw sends. Do not write genuine payload after leaving repair. Report restoration errors through the existing error discipline. Do not claim external acceptance from pure packet tests.
- Correct the documentation to describe startup failure without raw capabilities. Do not introduce an implicit split downgrade for explicit masks.

## Contracts and ownership

- Runtime worker owns F1/F3 in configuration, desync runtime and Kotlin chain validation/tests. IPC worker alone owns root-helper protocol capability metadata and bulk framing/tests. Emitter worker owns privileged sender and narrow sender tests. Integrator owns native docs and all planning records.
- A narrow method on the existing TcpChainStep owns the shared Rust settings rule. The runtime worker owns the generated ripdpi-config API snapshot if this addition changes it. No new trait hierarchy is introduced.
- No protobuf, JNI, storage schema, Cargo.lock, dependency version, locale, golden or architecture-baseline edits are planned. No new async runtime: TCP work remains synchronous on per-connection OS threads.
- Relevant locked versions: ripdpi local crates 0.1.0; compiler pin 1.98.1. Workers read exact dependency versions from Cargo.lock before edits.

## Risks / Trade-offs

- Conservative unknown-commit reporting can suppress retries before a raw attempt. This is safer than replay; retain preflight/validation distinctions where the existing API can prove them.
- Repair queue behavior needs Linux kernel acceptance. Use non-privileged local tests first and name privileged/device checks that remain unrun.
- Existing accepted but ignored conflicting settings now fail validation. Error messages and owning docs must state the grouped rule.
- Shared helper metadata remains backward compatible; old peers retain control-sized behavior.

## Migration Plan

No persisted-data migration. Existing first-marker settings with later defaults remain valid. Revert the finding commit to roll back its behavior, preserving the conservative replay guard unless a reviewed replacement exists.

Validation: targeted cargo test --locked for changed crates, :core:data:model tests if Kotlin validation changes, Rust format/clippy for affected crates, just ci-gates and python3 scripts/ci/check_architecture_health.py plus cargo metadata --locked before main integration. Independent pr-reviewer reviews substantial changes before each implementation commit. No hosted CI is triggered solely for testing; a normal authorized main push can trigger repository workflows.
