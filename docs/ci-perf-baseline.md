# CI Performance Baseline

Captured 2026-05-15 after sustained Tier 1–4 CI hardening and runner optimization.

## Measurement method

```bash
gh run list --workflow CI --limit 50 --json conclusion,createdAt,updatedAt
```

Wall time = `updatedAt - createdAt` per run. Cancelled runs excluded.

## Results (last 50 CI runs, 28 completed)

| Metric | Value |
|---|---|
| Sample size | 28 completed of 50 |
| p50 | 45 min |
| p95 | 60 min |
| Min | 42 min |
| Max | 85 min |

Typical successful run: **42–50 min**. The p95 figure has improved significantly from 616 min to 60 min due to stabilized Android integration emulator setup and build-logic caching.

## Recent runs (last 10)

| Conclusion | Duration | Commit |
|---|---|---|
| success | 48 min | docs(testing): cleanup stale feature-test audit and evidence |
| success | 45 min | docs(testing): record release gate progress |
| success | 47 min | docs(testing): refresh feature-test CI evidence |
| success | 44 min | fix(app): make GitHub update provider lint-visible |
| success | 52 min | docs(testing): update feature-test sign-off readiness |
| success | 49 min | feat(diagnostics): add full-matrix audit assessment |
| success | 55 min | fix(native): resolve monitor-engine budget hotspots |
| success | 43 min | refactor(app): split diagnostics UI builders |
| success | 46 min | fix(ci): stabilize android integration harness |
| success | 50 min | feat(relay): add Finalmask noise mode support |

## Next measurement

Re-run after two consecutive weeks of clean main runs:

```bash
gh run list --workflow CI --limit 50 --json conclusion,createdAt,updatedAt \
  | python3 -c "
import json,sys
from datetime import datetime
data=json.load(sys.stdin)
completed=[r for r in data if r['conclusion'] not in ('cancelled',None,'')]
durations=sorted([int((datetime.fromisoformat(r['updatedAt'].replace('Z','+00:00'))-datetime.fromisoformat(r['createdAt'].replace('Z','+00:00'))).total_seconds()/60) for r in completed])
print(f'n={len(completed)} p50={durations[len(durations)//2]}min p95={durations[int(len(durations)*.95)]}min')
"
```

Target: p50 ≤ 48 min (≤ 6% regression from current baseline), zero unexpected cancellations.
