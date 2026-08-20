# Task management — RIPDPI

RIPDPI uses a two-level, Git-native workflow. Portfolio state lives in Markdown task records; implementation changes that alter behavior or contracts additionally use OpenSpec. Execution checkboxes are indexed by mdtask.

## Canonical structure

| Path | Contract |
|---|---|
| `issues/<slug>.md` | Portfolio source of truth: state, priority, ownership, dependencies, and acceptance criteria |
| `work/<TASK-ID>.md` | mdtask execution steps for work that does not require OpenSpec |
| `../../openspec/changes/<change>/tasks.md` | mdtask execution steps for OpenSpec-backed work |
| `board.md` | Generated portfolio view; never edit by hand |

Install the exact repository tools with `just task-tools`. Run `./taskctl --help` for the lifecycle CLI and `just task-check` for the complete local contract gate. No global mdtask or OpenSpec installation is required, and OpenSpec telemetry is disabled by `taskctl`.

## Portfolio schema

```yaml
---
id: DGN-1786234567890123
title: Imperative task title
kind: feature
status: doing
area: diagnostics
priority: high
owner: Role name
parent: EPC-1786234567890001
blocked_by: []
spec_mode: required
openspec_change: dgn-1786234567890123-diagnostics-redesign
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

- `kind`: `feature | bug | chore | research | epic`.
- `status`: `backlog | todo | doing | review | blocked | done | dropped`.
- `priority`: `critical | high | medium | low`.
- `area`: `engine | rust-native | diagnostics | transport | outbound | dns | routing | vpn | proxy | relay | android | ui | data | service | testing | ci | epic`.
- `parent`, `blocked_by`, and local `linked_task` values use stable IDs. `blocked_by` is canonical; reverse `blocks` is derived.
- A blocked task needs an in-repository blocker or a non-empty `status_detail` describing its external gate.
- `source_wiki_pages`, `linked_task`, `status_detail`, and `status_note` are optional provenance/status fields.
- `done` and `dropped` additionally require `closed_at`, `closed_reason`, and `evidence_summary`.

The numeric suffix is a sortable allocation derived from UTC epoch milliseconds plus three random digits. It is globally unique across portfolio and execution IDs, even when prefixes differ. Worktrees belonging to the same Git repository coordinate allocations through a locked reservation file in the shared Git directory; the committed validator remains the cross-clone collision oracle. Area prefixes are enforced by `taskctl`: `ENG`, `RST`, `DGN`, `TRN`, `OUT`, `DNS`, `RTE`, `VPN`, `PRX`, `RLY`, `AND`, `UIX`, `DAT`, `SVC`, `TST`, `CIC`, and `EPC`.

## OpenSpec decision

`spec_mode: required` is mandatory for features, behavioral epics, user-visible behavior, breaking contracts, protobuf/JNI/wire/storage/configuration schemas, cross-module changes, and security, privacy, VPN, network, protocol, or service-lifecycle behavior.

`spec_mode: not-required` is limited to bugs, chores, or research with one of these explicit reasons:

- `regression-tested-single-module`;
- `test-only`;
- `docs-only`;
- `dependency-only`;
- `mechanical-refactor`;
- `tooling-only`;
- `research-only`.

Required changes use the repository schema `ripdpi-change`: `proposal.md → delta specs → design.md → mdtask tasks.md → verification.md`. Every proposal and verification artifact links back to the portfolio ID. The archive gate requires all execution steps, strict OpenSpec validation, an exact commit SHA, and resolved local/CI/device/artifact/deployment evidence.

## Lifecycle

```bash
./taskctl ready
./taskctl new --title "..." --kind bug --area ci --priority high \
  --spec-mode not-required --spec-reason tooling-only
./taskctl start <TASK-ID> --owner "Role name"
./taskctl steps <TASK-ID> list
./taskctl transition <TASK-ID> review
./taskctl verify <TASK-ID>
./taskctl generate-board
./taskctl validate
```

Completing every mdtask checkbox advances portfolio work at most to `review`; it never proves acceptance by itself.

OpenSpec completion uses:

```bash
./taskctl verify <TASK-ID> --archive-ready
./taskctl openspec archive <change-name>
./taskctl close prepare <TASK-ID> --outcome done --evidence "<observed evidence>"
```

Commit the prepared terminal record. Only afterward run `./taskctl close purge <TASK-ID>` and commit the deletion separately. CI inspects Git history and rejects deletion without the preceding terminal commit. Direct upstream archive, `--no-validate`, manual change-directory moves, and mdtask archive/ID assignment are unsupported.

A cancelled item uses the explicit dropped path:

```bash
./taskctl close prepare <TASK-ID> --outcome dropped \
  --reason "<owner decision>" --evidence "<decision evidence>"
git commit  # terminal record plus close/drop receipts
./taskctl openspec archive <change-name>  # uses --skip-specs for dropped changes
./taskctl close purge <TASK-ID>
git commit  # archived change plus portfolio deletion
```

`close prepare` preserves unfinished execution lines as `DROPPED` records rather than marking them complete. A dropped OpenSpec change is validated and archived for history but is not synchronized into normative `openspec/specs/`.

## Parallel work and validation

Before writers start, record module/path ownership and serialized shared-file lanes in the portfolio body. Each implementation runs in a dedicated worktree. Use `./taskctl graph` for parent/blocker relationships and `./taskctl ready` for the unblocked frontier.

The `task-contracts` CI job, Lefthook, and `just task-check` all call the same validator. It checks the strict schema, IDs, reference cycles, execution ownership, mdtask warnings, OpenSpec strict validation, verification records, generated skill hashes, board freshness, and deletion history.

## Tool licenses

OpenSpec 1.9.0 is MIT-licensed. mdtask 0.1.17 uses PolyForm Shield 1.0.0 and is pinned solely as an internal development tool; its noncompete and required notice are recorded in `tools/tasking/THIRD_PARTY_NOTICES.md`. A dependency upgrade or merge that introduces mdtask requires explicit owner/legal approval; do not infer approval from passing CI.
