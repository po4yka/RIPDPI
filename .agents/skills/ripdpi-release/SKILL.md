---
name: ripdpi-release
description: Prepare, build, publish, and verify one exact-SHA RIPDPI Android release through the repository release contract. Use only when the user explicitly asks to prepare or publish a specific version. Never invoke implicitly and never expose signing secrets.
---

# RIPDPI Release

Release one exact commit through the workflows declared in
`quality/release-gates/release-contract.json`. Run
`python3 scripts/ci/check_release_contract.py` before trusting this guidance; stop
if the contract and current source disagree.

## Resolve the release identity

Never carry a version snapshot in this skill. Derive and record:

- the user-requested target version and `vX.Y.Z` tag;
- the latest reachable stable tag as the changelog base;
- `ripdpiVersionName` and `ripdpiVersionCode` from `app/build.gradle.kts`;
- the exact `origin/main` and candidate SHAs;
- live remote tag, release, workflow, and asset state.

The target tag must match the requested version, and a Play version code must
increase. Stop on ambiguity, divergence, or an existing target tag/release.

## Authority boundary

Treat these as separate external operations:

1. **Inspect and prepare** — create a dedicated worktree, update source, validate,
   and commit locally.
2. **Integrate candidate** — rebase, fast-forward `main`, and push `main`; require
   explicit authorization under `AGENTS.md`.
3. **Produce candidate** — dispatch `.github/workflows/release-candidate.yml` for
   the exact remote `main` SHA and requested `release_tag`.
4. **Publish** — bind the successful candidate run, create and push the immutable
   tag, and verify the resulting release; require explicit authorization showing
   the exact SHA, tag, candidate run, assurance profile, and notes path.

A request to prepare does not authorize steps 2–4. Never print, copy, decode, or
inspect signing secret values; they are consumed only by the signing environment.

## Assurance profiles

Use the profile names and meanings from the release contract:

- `artifact-publish` is the automated release-blocking profile: exact-SHA main CI,
  secret-free release verification, immutable signed candidate, manifest,
  signatures, ELF checks, attestations, and tag-bound promotion;
- `device-qualified` adds separately recorded emulator, physical-device, or
  owner-lab evidence without silently changing artifact-publish results;
- `owner-accepted` records an explicit owner decision accepting named evidence
  gaps. It never turns FAIL, skipped, or missing evidence into PASS.

State the selected profile in the release handoff. Do not require a deleted or
nonexistent physical-evidence workflow.

## Prepare in a worktree

1. Read `AGENTS.md`, `quality/release-gates/release-contract.json`, both declared
   workflows, `docs/distribution.md`, and
   `quality/release-gates/app-identity-review.json` from the candidate tree.
2. Create a dedicated worktree from current `origin/main`; preserve unrelated
   changes in every other checkout.
3. Update `ripdpiVersionName` and `ripdpiVersionCode` in `app/build.gradle.kts`.
4. Refresh the application identity review from its recorded sources and resolved
   variants. Do not rubber-stamp only version/date fields.
5. Run `$release-changelog` over the immutable base-tag-to-candidate range and
   keep drafts outside tracked source unless the repository adopts a notes path.
6. Obtain approval for the curated notes before publication.
7. Commit the coupled version/identity preparation atomically, normally as
   `chore(release): prepare X.Y.Z`.

## Cut a bounded release window

Before the first candidate, freeze features and record the exact cut commit and
UTC timestamp in repository variables `RIPDPI_RELEASE_WINDOW_START_SHA` and
`RIPDPI_RELEASE_WINDOW_STARTED_AT`. The cut SHA must be on `main` and remain an
ancestor of every candidate.

The machine-readable `releaseWindow` contract allows at most 72 hours, 20
post-cut commits, and five candidate runs for one tag. Post-cut subjects are
limited to release-safe `fix`, `test`, `docs`, `ci`, `build`, and
`chore(release)` commits. A feature or refactor requires abandoning the window,
integrating normally, and starting a new cut; never relabel a commit to evade the
policy.

## Validate and integrate

Run the smallest release-specific gates first:

```bash
python3 scripts/ci/check_release_contract.py
./gradlew :app:writeReleaseIdentityManifest
python3 -m unittest scripts.tests.test_app_identity_review
python3 scripts/ci/check_app_identity_review.py --report
./gradlew :app:verifyReleaseVersion -Pripdpi.releaseRefName=vX.Y.Z
python3 -m unittest \
  scripts.tests.test_release_contract \
  scripts.tests.test_release_p0_contracts \
  scripts.tests.test_release_window \
  scripts.tests.test_release_artifact_uploads
git diff --check <base-tag>..<candidate-sha>
```

The canonical local mirror is:

```bash
just release-preflight vX.Y.Z <window-start-sha> <window-started-at-utc>
```

It must complete the secret-free GithubFullRelease and release AndroidTest build
and write `build/reports/release/preflight.json`. Its PASS is host-ABI evidence
only: the receipt explicitly says it did not sign artifacts and does not replace
hosted exact-SHA CI. Never call a partial manual subset the local preflight.

Run the applicable broader CI, architecture, Cargo-lock, and release-verification
gates. Do not use `-Pripdpi.skipNativeBuild=true` as production artifact evidence.
Review the final diff for unintended files, generated drift, sensitive material,
changed release tasks, and inaccurate notes.

After authorized rebase and fast-forward integration, push `main`, verify remote
SHA equality, and wait for successful `ci-required` on that exact SHA.

## Produce the immutable candidate

Dispatch `.github/workflows/release-candidate.yml` on `main` with:

- `release_tag=vX.Y.Z`;
- the workflow ref resolving to the exact candidate SHA.

The workflow must pass its exact-SHA CI preflight before entering expensive
producer/signing jobs. Select the run by workflow path, event, ref, creation time,
and `headSha`; never select an arbitrary newest run. Require successful candidate
manifest verification, signer continuity, APK/AAB signature verification, native
ELF verification, and attestations. Record the run ID and source SHA.

Additional device/lab evidence is collected and reported only when the selected
assurance profile calls for it. Never substitute debug or different-SHA evidence
for the signed candidate.

## Publish the tag-bound candidate

Before publishing, present:

| Field | Required value |
|---|---|
| Version / versionCode | exact reviewed values |
| Candidate SHA | local and remote `main` SHA |
| Tag | absent locally and remotely |
| Main CI | successful `ci-required` for candidate SHA |
| Candidate | successful run ID for candidate SHA |
| Assurance profile | contract-defined name and evidence gaps |
| Release notes | approved file path and digest |

After explicit authorization:

1. Bind repository variable `RIPDPI_RELEASE_CANDIDATE_RUN_ID` to the exact
   successful candidate run if changing it is within the granted authority.
2. Re-resolve every manifest value and stop on drift.
3. Create annotated tag `vX.Y.Z` at the candidate SHA and verify its peeled commit.
4. Push only that tag and reverify the remote peeled SHA.
5. Identify the tag-triggered `.github/workflows/release.yml` run by path, event,
   tag, and exact `headSha`.

Never move, recreate, or force-push a published release tag. Preserve evidence on
failure; prepare a new patch version when source bytes must change.

## Final verification

Require a successful tag workflow, a non-draft/non-prerelease GitHub Release, and
the exact asset inventory declared by the live workflow. Wrap download and
verification commands with `scripts/ci/with-transient-release-downloads.sh` and
write only below its `RIPDPI_RELEASE_DOWNLOAD_DIR`; the helper removes that exact
managed directory on exit. Verify `SHA256SUMS`, attestations, `update.json`,
version, package identity, URLs, and signer continuity. Apply the approved curated notes
without adding unsupported claims, then re-read the live release.

Report source/tag proof, local gates, exact-SHA main CI, candidate run, selected
assurance profile, publish run, release URL/assets, checksums/attestations,
device/lab evidence when requested, store publication status, and every remaining
gap. Never call a local build, debug APK, emulator run, or changelog proof that a
release shipped.
