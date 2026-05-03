# CI Performance Baseline

Captured 2026-05-03 after Tier 1–4 CI hardening (concurrency fix, matrix dedup,
timeouts, cache, SHA-pin, SBOM, API snapshot gate).

## Measurement method

```bash
gh run list --workflow CI --limit 50 --json conclusion,createdAt,updatedAt
```

Wall time = `updatedAt - createdAt` per run. Cancelled runs excluded.

## Results (last 50 CI runs, 17 completed)

| Metric | Value |
|---|---|
| Sample size | 17 completed of 50 |
| p50 | 60 min |
| p95 | 616 min |
| Min | 45 min |
| Max | 616 min |

Typical successful run: **45–60 min**. Outliers (165–616 min) are caused by
android-integration-tests emulator setup; these dominate the p95 figure.

## Recent runs (last 10)

| Conclusion | Duration | Commit |
|---|---|---|
| in_progress | ~12 min | fix(test): stabilize android integration harness |
| cancelled | 63 min | fix(ci): apply android boring-sys patch |
| cancelled | 70 min | fix(ci): bind instrumentation engine facade fakes |
| cancelled | 51 min | fix(ci): allow engine access for instrumentation tests |
| cancelled | 7 min | fix(ci): compile android instrumentation tests |
| cancelled | 58 min | fix(test): refresh config round trip fixtures |
| cancelled | 1 min | fix(test): align app startup expectations |
| cancelled | 50 min | fix(ci): normalize generated avd metadata |
| cancelled | 16 min | fix(ci): enforce emulator boot deadline |
| cancelled | 14 min | fix(ci): bound emulator boot waits |

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

Target: p50 ≤ 65 min (≤ 8% regression from baseline), zero unexpected cancellations.
