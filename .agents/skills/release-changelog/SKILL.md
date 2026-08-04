---
name: release-changelog
description: Collect a read-only, exact-range evidence pack and draft curated RIPDPI release notes from Git history, changed files, source, tests, and merged pull requests. Use when preparing a changelog, GitHub Release description, Play/F-Droid notes, or an audit of changes between a stable tag and a candidate SHA. Never tag, push, edit a release, or publish from this skill.
---

# RIPDPI Release Changelog

Build release notes from verifiable repository evidence. Treat commit subjects as leads, not proof of shipped behavior. Keep this skill read-only with respect to Git, GitHub, and release state.

## Establish the exact range

1. Confirm the repository root, branch/worktree, and status.
2. Fetch tags when network access is available. Do not modify branches.
3. Resolve the base stable tag and target commit to immutable object IDs.
4. Require the base commit to be an ancestor of the target. Stop on a divergent range.
5. Record both IDs in every draft so later edits cannot silently change scope.

Use the latest stable tag that is an ancestor of the candidate as the base, unless
the user explicitly selects another compatible stable tag. Use the immutable
release-candidate SHA as the target; do not assume `HEAD` is still that candidate
after a resume.

Generate the local evidence pack:

```bash
python3 .agents/skills/release-changelog/scripts/collect_release_changes.py \
  --base <base-tag> \
  --target <candidate-sha> \
  --output <temporary-directory>/vX.Y.Z-evidence.md
```

The script only reads Git data. It reports the exact range, commit taxonomy, changed components, diff totals, changelog candidates, and the complete commit inventory. Run without `--base` only when the latest reachable stable tag is the intended base.

## Add GitHub context

When authenticated GitHub access is available, inspect rather than mutate:

```bash
gh release view <base-tag> --repo po4yka/RIPDPI --json tagName,name,publishedAt,body,assets
gh pr list --repo po4yka/RIPDPI --state merged --base main --limit 1000 \
  --json number,title,mergedAt,mergeCommit,labels,author,url
```

Keep only PRs whose `mergeCommit.oid` belongs to `base..target`, or whose squash commit can otherwise be proven to belong to the range. Do not include a PR merely because its merge date overlaps the release window. If GitHub data is partial or unavailable, state that gap and rely on the complete local commit inventory.

## Verify user-facing claims

For every proposed highlight:

1. Identify the implementing commit or commits.
2. Inspect the final source at the target SHA, not only the patch or task note.
3. Locate the relevant test, contract fixture, migration, or UI evidence.
4. Determine whether the behavior is enabled and user-reachable in a published `FullRelease` variant.
5. Record compatibility, migration, security, privacy, distribution, and root/non-root implications.
6. Omit or qualify any claim that lacks final-tree evidence.

Check these sources when relevant:

- `app/build.gradle.kts` for version name/code and variants;
- `quality/release-gates/release-contract.json` for the current candidate and publication workflows;
- `.github/workflows/release-candidate.yml` for built artifacts and candidate gates;
- `.github/workflows/release.yml` for tag-bound promotion;
- `docs/distribution.md` for channel and identity contracts;
- `docs/architecture/` plus current Kotlin/Rust registries for protocol claims;
- tests and golden fixtures for behavioral proof;
- `git diff --check <base>..<target>` and the evidence pack for scope.

Never infer release readiness from the changelog. Exact-SHA CI, signed artifacts,
the selected assurance profile, and a successful release run are separate claims.

## Curate the GitHub Release description

Follow the style established by recent stable RIPDPI releases:

- lead with the release theme and user impact;
- place 4–8 strongest verified changes in `Highlights`;
- group the rest by user-facing domain such as security/privacy, connectivity, diagnostics/UX, stability, and performance;
- add distribution or upgrade notes only when behavior actually changed;
- summarize build/CI maintenance briefly;
- end with artifacts and an exact compare link;
- include commit or PR links for unusually specific or high-risk claims;
- avoid dumping every conventional commit into the curated portion.

Use this structure unless the evidence suggests a smaller release:

```markdown
# RIPDPI vX.Y.Z

`versionCode N` · M commits since [vA.B.C](compare-url)

One concise release-theme paragraph.

## Highlights
- Verified user-facing change.

## Security & privacy
- Verified security or privacy change.

## Connectivity, diagnostics & UX
- Verified behavior change.

## Stability & performance
- Verified fix or measured improvement.

## Build & internal
- Short maintenance summary.

## Artifacts
Describe only artifacts produced by the live release workflow.

**Full changelog:** exact compare URL
```

Do not repeat promotional circumvention claims. Describe concrete reliability, privacy, interoperability, and user-control changes. Do not expose secrets, private infrastructure, unpublished vulnerabilities, or sensitive evidence details.

## Produce store notes

Derive store notes from the approved curated description, not directly from commit subjects. Keep them plain text and user-visible. Before creating any new Play metadata path, re-check the repository for an existing convention; do not introduce a new publishing layout merely to hold a draft.

## Handoff

Return:

- base tag and resolved commit;
- target ref and resolved commit;
- total commit and diff counts;
- verified highlights with source/test evidence;
- omitted or uncertain claims;
- GitHub Release draft;
- store-note drafts if requested;
- remaining evidence or review gaps.

Do not create tags, push refs, dispatch workflows, edit GitHub Releases, or upload artifacts. Hand the approved notes and exact target SHA to `$ripdpi-release`.
