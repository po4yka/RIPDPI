# CI failure triage

Use this route for a failed GitHub Actions check. For coverage planning, use the [testing overview](../testing.md) and [feature test checklist](../feature-test-checklist.md).

## 1. Identify the run and revision

Start with `just checkout-doctor`. Open the failed check's run URL from the PR or commit; if investigating the current checkout, list its runs:

```bash
gh run list --commit "$(git rev-parse HEAD)" --limit 10 \
  --json databaseId,workflowName,headSha,status,conclusion,url
```

Replace `RUN_ID` below with the selected run ID. Record its SHA, event, branch, attempt, and workflow before reading logs:

```bash
ci_run_id=RUN_ID
gh run view "$ci_run_id" \
  --json headSha,event,headBranch,attempt,workflowName,status,conclusion,url,jobs \
  --jq '{sha:.headSha,event,branch:.headBranch,attempt,workflow:.workflowName,status,conclusion,url,failedJobs:[.jobs[] | select(.conclusion != "success" and .conclusion != "skipped" and .status == "completed") | {id:.databaseId,name,conclusion,url}]}'
```

A PR run can test a merge revision different from local `HEAD`. Read the job's checkout step and workflow at the tested revision; today's workflow may have changed since that run. Keep any reproduction checkout isolated from unrelated work.

## 2. Find the first failing step

Replace `ATTEMPT` and `JOB_ID` with values from the selected run, then read that job's failed steps:

```bash
ci_attempt=ATTEMPT
ci_job_id=JOB_ID
gh run view "$ci_run_id" --attempt "$ci_attempt" --job "$ci_job_id" --log-failed
```

Use `--log` instead of `--log-failed` on the same job when setup or preceding steps supply missing context. Logs may remain unavailable while the job is running. Follow the first actionable error, including its file, task, command, and exit code.

For `ci-preflight` or `ci-required`, inspect upstream job results through the workflow's `needs` and `if` expressions. Fix the originating failure before the aggregate check. If the aggregate itself rejects routing or a skipped required job, compare the run's changed paths with the [routing script](../../scripts/ci/resolve_change_routing.py), [preflight check](../../scripts/ci/check_ci_preflight.py), and [CI workflow](../../.github/workflows/ci.yml) at that revision.

## 3. Choose the smallest relevant reproduction

Read the failed step's actual command and called script. Derive task names, flags, tool versions, and matrix inputs from that workflow; avoid remembered commands or an entire local CI sweep.

| Failure | Next source or check |
| --- | --- |
| Gradle/Kotlin or native build | Failed Gradle task or script; [build setup and performance](build-performance.md) for checkout and environment issues |
| Rust lint or tests | Failed script and [workspace manifest](../../native/rust/Cargo.toml); retain `--locked` when Cargo resolves dependencies |
| Android/device or UI automation | Run-specific test reports, logcat, emulator, or automation artifacts named by the upload step; [automation guide](../automation/README.md) for the affected suite |
| Golden mismatch | Inspect expected/actual/diff artifacts and follow [golden discipline](../../.claude/rules/golden-bless-discipline.md) before changing fixtures |
| Agent harness | [Harness maintenance](../../.claude/rules/harness-maintenance.md) and `just harness-check` |

On this Mac, wrap every heavy local build/test in `build-gate -- <command>` and respect the [worker limits](build-performance.md#concurrency-ceiling). Preserve the failing command's working directory and inputs. A host-native pass does not reproduce a hosted Linux, ABI, emulator, or device failure by itself.

For a supported workflow requiring local Actions reproduction, first run `scripts/ci/act-local.sh --list`, then follow [local CI with act](../../.agents/skills/local-ci-act/SKILL.md) for that job. Verify artifact provenance against the recorded SHA and attempt; artifacts from another attempt can remain attached to the same run.

## 4. Confirm the fix

Run the relevant local gate after the correction. After an authorized push, select the run for the new SHA and inspect its attempt and job results again. Hosted acceptance requires `status: completed` and the required checks' successful conclusions; a queued/running check or local pass is incomplete evidence. `gh run view --exit-status` alone also returns success for a pending run.

Report the original run/attempt/job and first error, the changed source, local check result, and the new SHA's terminal hosted status (or the exact pending/blocked check). Rerunning unchanged code alone does not establish a fix.
