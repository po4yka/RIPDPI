## Context

Task ID: `EPC-1793256695505524`. See proposal.md and the portfolio task for the owner decisions made on 2026-10-29.

## Goals / Non-Goals

- Goal: sequence four child changes so each lands on a verified base.
- Non-goal: change network, service, native, JNI, or storage behavior beyond the dynamic color setting that the theme foundation adds.

## Decisions

- Phases run in order: theme foundation, app shell, component facade, screen layouts. Each child is blocked by the previous one.
- The three UI changes in review (`material3-readability`, `connection-control-redesign`, `audited-ui-quality`) close before the theme foundation starts.
- Work, commits, and push happen directly on `main` by owner authorization. Parallel screen work in the last phase uses separate worktrees with one writer for each serialized file.

## Contracts and ownership

- Serialized files: `gradle/libs.versions.toml`, the AppSettings proto, all locale resource sets, theme files under `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/`, and screenshot fixtures.
- Golden fixtures change only after owner approval for each phase.

## Risks / Trade-offs

- Material 3 1.5 is beta. Record resolved versions in each child change.
- Large fixture churn. Review each phase's diffs separately.
