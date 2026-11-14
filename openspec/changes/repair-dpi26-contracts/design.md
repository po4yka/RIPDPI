## Context

Task RST-1794677647125288 repairs three confirmed audit findings on main df2469ed421862d0a0422d9a086d6c4e7bb5116d.

## Goals / Non-Goals

- Goal: preserve failure boundaries, ID policy and admission checks.
- Non-goal: extend UDP/round/profile scope or activate root or external probes.

## Decisions

- Reuse existing fragment error metadata without a schema/version change. Mark pre-send errors and retain existing uncertain phases. Only typed pre-send InvalidInput permits plain fallback.
- Copy ID policy into synthetic UDP groups; retain the final ID allocator.
- Add root validation after existing VPN/CLI guards.
- Commit each finding separately and integrate after combined validation.

## Contracts and ownership

Primary integrator owns proxy config, Kotlin model/tests, task/spec artifacts and integration. Helper worker owns privileged UDP errors, shared formatter, client decoder, adapter fallback and tests in a separate worktree. Reviewers are read-only. Dependencies, schemas, locale sets, baselines and goldens stay unchanged.

## Risks / Trade-offs

Legacy replies cannot prove safe recovery and stay terminal. Root mode does not guarantee capabilities; native validation stays intact. Host tests do not prove privileged wire delivery.

## Migration Plan

No persisted data migration is needed. Ship the app/helper pair together; rollback the repairs together. Validate affected Rust/Kotlin tests, IPC contracts, locked metadata, independent review and just ci-gates. Attempt Linux IPC and Android compile where available. No physical/root/external test is authorized.
