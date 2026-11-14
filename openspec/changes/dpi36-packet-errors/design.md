## Context

The audit found two error-boundary defects and one inaccurate scope claim. See proposal.md.

## Goals / Non-Goals

- Goal: preserve existing delivery behavior only when an error proves that retry is safe.
- Non-goal: add packet mutation modes, change MTU fallback policy, coordinate fragment IDs across processes, or activate root/device tests.

## Decisions

- Reuse IpFragmentSendError, IpFragmentErrorData and the desync phase projection. For RST, uncertainty also covers unrestored socket state although original bytes have not been sent.
- Carry the existing error metadata for fake RST and UDP as well as TCP fragments. Missing or invalid metadata stays terminal. This is additive in helper protocol v4; old peers fail closed.
- Evaluate both cleanup operations before combining results. Propagate unsafe errors from both the plan and action executors.
- Correct MD5 prose instead of adding a new wire feature.

## Contracts and ownership

- The packet-repair integrator is the sole writer of production sources, tests, helper metadata, documentation and planning files. Subagents perform read-only analysis and review. No parallel writers own shared files.
- Affected crates: ripdpi-privileged-ops, ripdpi-root-helper-protocol, ripdpi-root-helper, ripdpi-runtime-platform, ripdpi-proxy-runtime-desync-adapter and ripdpi-desync-runtime.
- No public signatures, helper version constants, dependencies, Cargo.lock, locales, baselines, JNI or protobuf schemas change.

## Risks / Trade-offs

- Restored error kinds could enable unsafe retries: require an explicit safe phase.
- Legacy helper errors have no phase: retain a terminal result.
- Linux syscall branches cannot execute on Darwin: run host regressions and compile Android tests, and report privileged execution as unverified.

## Migration Plan

Deploy matched application/helper binaries as usual. The error metadata is additive and older peers remain terminal. Revert each repair commit if required; no stored-data migration exists.

## Validation

Run targeted regression tests before and after each fix, then combined owning-package tests and doctests, Clippy with --all-targets and -D warnings, Android aarch64 checks for Linux-owned branches, just ci-gates, architecture health and locked Cargo metadata. Run independent PR review before committing. Record exact-SHA evidence after source commits. Root, physical-device and external probes remain outside this repair's acceptance scope.
