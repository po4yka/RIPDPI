## Context

See `proposal.md`. Two diagnostic cards render enum names as English labels, and seven existing subscription failover strings are untranslated in seven locales.

## Goals / Non-Goals

- Goal: show localized labels and failover copy for the selected app locale.
- Non-goal: change diagnostic execution, subscription health logic, or screen layout.

## Decisions

- Map the four tool states to one shared set of app string resources at the Compose display boundary. This keeps model state independent of locale and avoids duplicate labels for the two cards.
- Translate the seven existing strings in place. No new navigation or resource structure is needed.

## Contracts and ownership

- Affected module: `:app` Kotlin Compose UI, unit tests, and string resources.
- Rust crates, JNI, protobuf, persistence, and public contracts: unchanged.
- Serialized shared files: ten app locale sets, owned by this branch until integration with the DNS and root-mode branches.

## Risks / Trade-offs

- A missing or malformed translation can show English fallback or fail resource compilation. Verify locale key parity and run app lint.
- Screenshot text may change in localized UI baselines. Inspect only; fixture updates follow the separate approval workflow.

## Migration Plan

No data migration or compatibility break. Reverting the commit restores prior labels and resource values. Run focused UI tests, locale parity, and `:app:lintGithubFullDebug` before integration.
