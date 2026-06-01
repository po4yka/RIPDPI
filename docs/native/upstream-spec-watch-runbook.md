# Upstream Spec Watch — Runbook

This runbook covers operating the weekly upstream-spec-watch workflow that validates each protocol crate's `SPEC_VERSION.md` pin metadata, protocol fixture tags, and the `SPEC.md` presence check.

## What the workflow does

Defined in `.github/workflows/upstream-spec-watch.yml`. Runs every Monday at 09:00 UTC and on-demand via `workflow_dispatch`:

1. Runs `scripts/ci/verify_spec_versions.py --format-only` to assert every protocol crate has a well-formed `SPEC_VERSION.md` with the required fields (`Upstream repo`, `Upstream tag`, `Upstream commit`, `Last reviewed`).
2. Runs `scripts/ci/check_protocol_fixture_versions.py` to assert checked-in protocol fixtures use tags pinned by their crate's `SPEC_VERSION.md`.
3. Runs `scripts/ci/verify_spec_md_present.sh` to assert every protocol crate ships a `SPEC.md` with at least an `Upstream` or `Standards` section.
4. Emits a `drift-report.md` artifact. This is currently a **placeholder** (format-only; it echoes the `SPEC_VERSION.md` pins rather than diffing them against live upstream releases) — full per-crate drift detection is expanded as the watch matures.

Currently the drift detection is format-only — the per-protocol upstream-release diffing (xray-core release feed, hysteria release tags, etc.) is staged in as those API integrations are added.

## When to act

### Verify step fails

- `verify_spec_versions.py` exits non-zero → a `SPEC_VERSION.md` is malformed. Open the named file and fix the missing field. The same script runs in PR CI so this should rarely fire on `main`.
- `check_protocol_fixture_versions.py` exits non-zero → a checked-in fixture directory uses an unpinned or stale upstream tag. Move the fixture under a tag declared in the matching crate's `SPEC_VERSION.md` or update the pin after review.
- `verify_spec_md_present.sh` exits non-zero → a protocol crate is missing `SPEC.md` or its `## Upstream` / `## Standards` heading.

### Pin-inventory report is ready for review

Until per-protocol release-feed integrations land, the report is a manual review prompt rather than an automatic upstream-release diff. For each protocol the report names:

- Upstream repo
- Upstream tag (last pinned)
- Last review date

When you (the native-runtime maintainer) manually review the upstream's release notes between the pinned commit and current upstream:

| Finding | Action |
|---|---|
| No wire change | Bump `Last reviewed:` in `SPEC_VERSION.md`. |
| Non-breaking wire addition | Bump `Last reviewed:` and `Upstream tag:`; consider whether RIPDPI should adopt the new field. |
| Breaking wire change | Open a task under `docs/tasks/issues/` and add `blocked_by:` to any client work that depends on the broken combination. Do not auto-bump the pin. |
| Field deprecation (xray-core flow deprecation, etc.) | Update the relevant validator or renderer task; the current Xray config validator is `core/data/catalog/src/main/kotlin/com/poyka/ripdpi/data/XrayConfigValidator.kt`. |

### Xray-core pin coordination with the deploy stack

The client's Xray surface — the vendored `:xray-protos` schemas and
`core/data/catalog/src/main/kotlin/com/poyka/ripdpi/data/XrayConfigValidator.kt` — must
stay compatible with the Xray-core version that the sibling `ripdpi-vpn-deploy` server
stack pins. That pin is the server's authoritative `xray.version`; the deploy repo tracks
the current value and breaking-change notes in its `docs/XRAY-RELEASE-LINE.md`.

There is no automated cross-repo assertion (each repo's CI can only read its own tree), so
this is a **manual gate** anchored here:

| Deploy-side event | Client-side action |
|---|---|
| Deploy bumps `xray.version` within the same wire-compatible line | No action; note it at the next pin review. |
| Deploy bumps across a wire-breaking boundary flagged in `XRAY-RELEASE-LINE.md` (e.g. a removed field such as `echForceQuery`, or a flow deprecation) | Open a `docs/tasks/issues/` task to update `XrayConfigValidator.kt` and re-vendor the affected `:xray-protos` `.proto` files; land it before the client claims support for the new server line. |

When reviewing the pin-inventory report, check the deploy repo's `XRAY-RELEASE-LINE.md`
delta against the last client review and apply the table above. The deploy repo owns a
mirror check that its documented release-line version matches its in-repo pin.

## Running on-demand

From the GitHub Actions UI, fire `Upstream Spec Watch` → `Run workflow` on `main`. The `upstream-spec-watch-report` artifact is retained for 30 days.

Locally:

```bash
just verify-spec-versions
```

Runs the workflow's local verification scripts in sequence.

## Owner

Native-runtime maintainer rotates ownership; the report is reviewed within 48 hours of firing.
