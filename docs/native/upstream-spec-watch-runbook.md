# Upstream Spec Watch — Runbook

This runbook covers operating the weekly upstream-spec-watch workflow that diffs each protocol crate's `SPEC_VERSION.md` pin against its upstream reference and the SPEC.md presence check.

## What the workflow does

Defined in `.github/workflows/upstream-spec-watch.yml`. Runs every Monday at 09:00 UTC and on-demand via `workflow_dispatch`:

1. Runs `scripts/ci/verify_spec_versions.py --format-only` to assert every protocol crate has a well-formed `SPEC_VERSION.md` with the required fields (`Upstream repo`, `Upstream tag`, `Upstream commit`, `Last reviewed`).
2. Runs `scripts/ci/verify_spec_md_present.sh` to assert every protocol crate ships a `SPEC.md` with at least an `Upstream` or `Standards` section.
3. Emits a drift report listing each crate's currently-pinned reference and uploads the report as a workflow artifact.

Currently the drift detection is format-only — the per-protocol upstream-release diffing (xray-core release feed, hysteria release tags, etc.) is staged in as those API integrations are added.

## When to act

### Verify step fails

- `verify_spec_versions.py` exits non-zero → a `SPEC_VERSION.md` is malformed. Open the named file and fix the missing field. The same script runs in PR CI so this should rarely fire on `main`.
- `verify_spec_md_present.sh` exits non-zero → a protocol crate is missing `SPEC.md` or its `## Upstream` / `## Standards` heading.

### Drift report shows a new upstream release

Until per-protocol release-feed integrations land, the drift report is a manual review prompt. For each protocol the report names:

- Upstream repo
- Upstream tag (last pinned)
- Last review date

When you (the native-runtime maintainer) review the upstream's release notes between the pinned commit and `main`:

| Finding | Action |
|---|---|
| No wire change | Bump `Last reviewed:` in `SPEC_VERSION.md`. |
| Non-breaking wire addition | Bump `Last reviewed:` and `Upstream tag:`; consider whether RIPDPI should adopt the new field. |
| Breaking wire change | Open a task under `docs/tasks/issues/` and add `blocked_by:` to any client work that depends on the broken combination. Do not auto-bump the pin. |
| Field deprecation (xray-core flow deprecation, etc.) | Update the host-pack validator per `recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes`. |

## Running on-demand

From the GitHub Actions UI, fire `Upstream Spec Watch` → `Run workflow` on `main`. The drift report artifact is retained for 30 days.

Locally:

```bash
just verify-spec-versions
```

Runs the two verify scripts in sequence.

## Related tasks

- `docs/tasks/issues/add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols.md` (done) — set up the pins and the workflow.
- `docs/tasks/issues/recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes.md` — host-pack validator integration.
- `docs/tasks/issues/tag-protocol-contract-fixtures-by-upstream-version.md` (blocked) — once wire-format fixtures exist, tag them by upstream tag so drift detection becomes structural.

## Owner

Native-runtime maintainer rotates ownership; the report is reviewed within 48 hours of firing.
