---
name: ripdpi-release
description: Prepare, gate, publish, and verify an exact-SHA RIPDPI Android app release through the repository's GitHub Actions flow. Use only when the user explicitly asks to prepare or create a specific RIPDPI version, including the planned 0.1.4 release. Coordinates versioning, identity review, changelog approval, CI, physical DNS/IPv6/kill-switch evidence, signed artifacts, tag creation, GitHub Release notes, checksums, and attestations. Never invoke implicitly and never publish without the release checkpoints in this skill.
---

# RIPDPI Release

Release one exact commit through the live repository workflow. Prefer a stopped, reviewable state over an ambiguous or partially verified release.

## Current planned release

Use these defaults only while the repository still has `v0.1.3`, `ripdpiVersionName = "0.1.3"`, and `ripdpiVersionCode = 11`:

- target version: `0.1.4`;
- target tag: `v0.1.4`;
- target version code: `12`;
- changelog base: `v0.1.3`.

If any default is stale, stop and ask for the exact target version. Never downgrade or reuse a version code. If the user supplies a different version, derive the base tag and next version code from live source and published release data.

## Authority boundary

Treat these as separate operations:

1. **Inspect** — read-only; safe when the skill is invoked.
2. **Prepare** — create a dedicated worktree, edit version/review files, validate, and commit locally.
3. **Integrate candidate** — rebase/fast-forward into `main` and push `main`; require explicit user confirmation under the repository worktree policy.
4. **Publish release** — create and push the tag, then edit the GitHub Release notes; require a second explicit confirmation showing the exact SHA, tag, evidence run, preflight run, and approved notes path.

The user's request to “prepare” does not authorize steps 3 or 4. A request to “release `0.1.4`” starts the workflow but still pauses at both irreversible/external checkpoints.

Never print, copy, decode, or inspect signing secret values. Use configured GitHub secrets only through the workflow.

## Phase 1: Inspect live state

1. Read `AGENTS.md`, `.github/workflows/release.yml`, `.github/workflows/dns-ipv6-killswitch-evidence.yml`, `docs/distribution.md`, and `quality/release-gates/app-identity-review.json` from the current candidate tree.
2. Confirm the supplied checkout, branch/worktree, and dirty state. Preserve unrelated changes.
3. Fetch `origin` and tags. Resolve `origin/main`, the base tag, and the candidate to full SHAs.
4. Query live GitHub state:
   - the target tag must not exist remotely;
   - the target GitHub Release must not exist;
   - no conflicting release workflow may be running;
   - the latest stable release and its assets must match the assumed base.
5. Confirm `gh auth status` without exposing tokens.
6. Check whether repository variables `RIPDPI_DNS_IPV6_KILLSWITCH_RESULTS` and `DNS_IPV6_KILLSWITCH_EVIDENCE_RUN_ID` exist. Report presence only; do not print or change their values.
7. Read the current ordinary-results status in `docs/testing.md` and `docs/tasks/issues/produce-android-ordinary-release-gate-results.md`. The dual-vantage evidence bundle and the 11 ordinary-results gates are separate inputs.
8. Record the exact expected artifact and gate contract from the live workflow. Do not rely on this skill's snapshot when source differs.

Stop on a pre-existing remote target tag/release, a non-ancestor base, an unknown version-code history, a source/workflow conflict, or an ordinary-results producer that can only emit fail-closed/NO-SHIP output. Never hand-author PASS results or weaken the checker to unblock a release.

## Phase 2: Prepare the candidate locally

Create a dedicated worktree from current `origin/main`. Do not edit the `main` checkout.

1. Update only `ripdpiVersionName` and `ripdpiVersionCode` in `app/build.gradle.kts`.
2. Refresh `quality/release-gates/app-identity-review.json` exactly as required by `docs/distribution.md`:
   - re-read all three recorded sources;
   - update source revisions, blob hashes, and review dates from verified content;
   - refresh the catalog and derived matches;
   - review every resolved release variant;
   - record the explicit identity decision and current risk.
3. Do not rubber-stamp the old identity review by changing only version/date fields.
4. Run `$release-changelog` against the base tag and the candidate SHA. Store its evidence pack and draft under a temporary directory, outside tracked source.
5. Curate the GitHub Release notes and optional store notes. Include the exact compare range and version code. Obtain user approval for the final notes before publication.
6. Commit the coupled version/identity preparation as one atomic Conventional Commit, normally `chore(release): prepare 0.1.4`. Do not commit temporary notes unless the repository has established a tracked release-note convention.

## Phase 3: Validate the local candidate

Run the smallest release-specific gates first, then the broader repository gates required by the current workflow:

```bash
./gradlew :app:writeReleaseIdentityManifest
python3 -m unittest scripts.tests.test_app_identity_review
python3 scripts/ci/check_app_identity_review.py --report
./gradlew :app:verifyReleaseVersion -Pripdpi.releaseRefName=v0.1.4
python3 scripts/tests/test_release_artifact_uploads.py
python3 scripts/tests/test_dns_ipv6_killswitch_gates.py
git diff --check <base-tag>..<candidate-sha>
```

Use the actual requested tag instead of `v0.1.4` when different. Run the applicable static analysis, unit tests, Rust gates, release-verification build, and architecture checks defined by current CI. Do not use `-Pripdpi.skipNativeBuild=true` as production artifact evidence.

Review the final diff for unintended files, stale generated outputs, secret material, changed release tasks, and inaccurate notes.

## Checkpoint A: integrate and push the candidate

Present:

- worktree branch and commit list;
- base tag and candidate SHA;
- version name/code;
- exact validation results and gaps;
- draft notes path;
- current CI/release-gate risks.

Ask explicitly for permission to rebase onto current `origin/main`, run combined-tree collision gates, fast-forward `main`, and push `main`. Do none of those actions before approval.

After approval, follow the canonical worktree integration sequence in `AGENTS.md`. Re-run combined-tree architecture, Cargo lock, identity, and release-version gates on the rebased candidate before fast-forwarding. Push `main`, then verify that the remote `main` SHA equals the local release-candidate SHA and required CI completes successfully for that exact SHA.

## Phase 4: collect exact-SHA physical evidence

Dispatch `.github/workflows/dns-ipv6-killswitch-evidence.yml` on the remote branch containing the candidate SHA. Select the resulting run by workflow, event, branch, creation time, and exact `headSha`; never take an arbitrary newest run.

Wait for completion and require:

- conclusion `success`;
- exact candidate `headSha`;
- a physical, non-emulator Android runner;
- the uploaded `dns-ipv6-killswitch-release-evidence` artifact;
- a manifest that validates for `android-client-release` and the run ID/attempt.

Record the evidence run ID and attempt. Never substitute an older scheduled run, emulator result, debug-only static check, or evidence from a different SHA.

Separately require a real exact-SHA ordinary-results file for all gates assigned to `ordinary-results`. It must be derived by the checked-in, reviewed raw-artifact verifier described in `docs/testing.md`; a structured all-FAIL placeholder is not release evidence. Confirm that the release workflow can access the file at the path represented by `RIPDPI_DNS_IPV6_KILLSWITCH_RESULTS` after downloading its evidence artifact. Do not set or change that repository variable without separate explicit authorization, and never point it at a local-only or missing path.

## Phase 5: run a no-publish release preflight

Dispatch `.github/workflows/release.yml` on the same remote candidate ref with:

- `create_release=false`;
- `gate_evidence_run_id=<exact evidence run ID>`.

Wait for completion. Require a successful run whose `headSha` equals the candidate. Inspect job logs and artifacts, not only the aggregate conclusion. Confirm that the workflow built signed Play/F-Droid/GitHub artifacts, metadata, SBOMs, checksums, mappings, native symbols, and attestations as currently configured.

If the preflight fails, fix the candidate through a new commit, repeat local/CI/evidence/preflight validation for the new SHA, and regenerate the changelog range if material behavior changed.

## Checkpoint B: publish the immutable tag

Present a compact release manifest:

| Field | Required value |
|---|---|
| Version / versionCode | exact reviewed values |
| Candidate SHA | remote `main` and local SHA |
| Tag | absent locally and remotely before creation |
| Main CI | success for candidate SHA |
| Physical evidence | successful run ID + attempt for candidate SHA |
| Ordinary results | real approved exact-SHA file + source-owned verifier |
| Repository variables | present and proven usable by preflight |
| Release preflight | successful no-publish run ID for candidate SHA |
| Release notes | approved file path + digest |

Ask for explicit confirmation to create annotated tag `vX.Y.Z` at that exact SHA and push only that tag. Do not combine this approval with unrelated pushes or deployment actions.

After approval:

1. Re-resolve every manifest value and stop on drift.
2. Create an annotated tag with message `Release vX.Y.Z` at the candidate SHA.
3. Verify the tag object and peeled commit locally.
4. Push only `refs/tags/vX.Y.Z`.
5. Verify the remote peeled tag still resolves to the candidate SHA.
6. Identify the tag-triggered Release run by workflow, `event=push`, tag, and exact `headSha`.

Never force-push, delete, move, or recreate a published release tag. If the tag-triggered workflow fails, preserve the tag and release evidence. Rerun only when the failure is transient and the source SHA remains valid; otherwise prepare a new patch version and report the failed release honestly.

## Phase 6: finalize and verify GitHub Release

Wait for the tag-triggered workflow to succeed. Then:

1. Verify the GitHub Release is non-draft, non-prerelease, and bound to the exact tag.
2. Compare attached asset names against the live `Create GitHub Release` workflow inputs.
3. Require the Play AAB, all expected F-Droid/GitHub APKs, `update.json`, `SHA256SUMS`, Android/Rust SBOMs, and size reports.
4. Download assets to a new temporary directory.
5. Recompute SHA-256 values and compare them with `SHA256SUMS`, accounting for workflow paths by basename.
6. Run `gh attestation verify <artifact> --repo po4yka/RIPDPI` for each published binary and metadata artifact covered by provenance.
7. Inspect `update.json` for version `X.Y.Z`, version code, package ID, safe filenames, hashes, and release URLs.
8. Replace the auto-generated body with the already approved curated notes using `gh release edit --notes-file`; do not improvise new claims after publication.
9. Re-read the live release and verify title, body, compare link, assets, and latest-release status.
10. Smoke-test the downloaded signed GitHub APK on the required physical device when the release acceptance bar calls for it. Report Play upload/publication separately; the GitHub workflow only produces the AAB.

## Final report

Report separately:

- source commit and tag proof;
- local gates;
- remote main CI;
- physical exact-SHA evidence;
- no-publish preflight;
- tag-triggered release run;
- GitHub Release URL and verified assets;
- checksum and attestation results;
- physical-device smoke evidence;
- Play/F-Droid publication status;
- any remaining blocker or unverified assumption.

Never call a local build, debug APK, emulator run, or generated changelog proof that the release shipped.
