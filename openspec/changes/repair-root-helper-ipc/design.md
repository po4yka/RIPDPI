## Context

Task VPN-1794196711383747 implements the VPN-09 repairs on main 7b475fb9e. The audit source was b2647d241; F3 and F4 now have an existing repair in 6808d8e91. Tests must use current callers and contracts. The user authorizes local implementation, separate commits, main integration, and push.

## Goals / Non-Goals

- Goal: Repair every reproduced IPC defect and retain the existing root and non-root contracts.
- Goal: Make each confirmed issue a separate Conventional Commit with observed regression evidence.
- Non-goal: Add replay sequence numbers, remote root access, new peer authority, or claim Android/root acceptance from host tests.
- Non-goal: Change the adjacent DPI-37 packet algorithms or VPN-10 firewall engine.

## Decisions

- Use an atomic close-on-exec descriptor replacement on Linux and Android. Handle ownership edge cases explicitly and test kernel flags and an exec child.
- Bound reconstruction by actual genuine bytes and validate range arithmetic before allocating. Do not impose an arbitrary MTU cap on existing direct callers.
- Reproduce H1/H2/H4 first. Use monotonic total budgets and socket metadata validation if the reproduction confirms the gaps. Keep one commit for each distinct issue.
- Retain current app-private directory trust. Record H3 pathname and H5 active-VPN raw-path uncertainty unless current evidence proves an in-scope defect. No root or physical/external probe is authorized.
- Verify F3/F4 against existing scoped cleanup and deadline tests; do not duplicate their commits.

## Contracts and ownership

- fd_cloexec_fix owns privileged-ops/src/linux/fd.rs, tcp_repair.rs and owning fd tests in worktree vpn09-cloexec-20261109.
- ordered_limits_fix owns privileged-ops/src/linux/raw_packet/tcp_payload.rs and owning raw-packet tests; it may add helper ordered-command regression tests. It does not edit helper main.rs or dispatch.rs.
- protocol writer owns root-helper main.rs, dispatch socket validation, root-helper-protocol bounded receive, runtime-platform client transport, and their tests in its separate worktree. It coordinates any helper test path with ordered_limits_fix.
- Integration writer owns portfolio and OpenSpec artifacts, ROOT_HELPER_CONTRACT.md, combined validation and integration. No parallel writer edits these paths.
- Serialized shared files (lockfiles, schemas, registries, baselines, golden fixtures, locales) are not edited.
- Public wire commands, version 3 and capability version 2 remain stable. No persistence or JNI migration is planned.

## Risks / Trade-offs

- Mac tests omit Linux code through cfg; run Linux tests as an unprivileged user with no external network.
- Unsafe descriptor changes require independent pr-reviewer and unsafe-code-auditor before commit.
- Total deadline checks must close received descriptors on error without changing ambiguous-delivery fallback semantics.
- Resource limits and process fixtures must be isolated and must not invoke su or global process-name cleanup.

## Migration Plan

No data or wire migration is needed. Roll back only complete repair commits. Gates: narrow RED/GREEN regressions, affected Cargo tests and clippy with --locked, owning Kotlin tests, just ci-gates, architecture-health --check, cargo metadata --locked, ARM64 helper build, and independent review. Validate again after any rebase that changes inputs. Keep the task in review while hosted CI or device acceptance remains unobserved.
