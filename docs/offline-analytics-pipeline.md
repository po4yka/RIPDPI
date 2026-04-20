# Offline Analytics Pipeline

This runbook covers RIPDPI's repo-local offline analytics pipeline for mining new censorship signatures and mapping reproducible winning strategies to offline device fingerprints.

The pipeline is intentionally offline and review-gated:

- inputs are exported diagnostics archives or extracted archive directories
- clustering and signature mining run in repo-local Python scripts
- outputs are analyst reports plus unreviewed candidate catalogs
- the app only sees blessed reviewed artifacts checked into the repo
- runtime strategy selection does not consume these artifacts yet

## Workspace Layout

Pipeline code:

- `scripts/analytics/pipeline.py`
- `scripts/analytics/extract.py`
- `scripts/analytics/cluster.py`
- `scripts/analytics/publish.py`
- `scripts/analytics/bless.py`

Checked-in sample corpus:

- `scripts/analytics/sample-corpus.json`
- `scripts/analytics/sample_corpus/`

Schemas:

- `scripts/analytics/schemas/offline-record-schema-v1.json`
- `scripts/analytics/schemas/device-fingerprint-catalog-schema-v1.json`
- `scripts/analytics/schemas/device-fingerprint-winner-mappings-schema-v1.json`

Reviewed bundled artifacts:

- `app/src/main/assets/offline-analytics/device-fingerprint-catalog.json`
- `app/src/main/assets/offline-analytics/device-fingerprint-winner-mappings.json`

## What The Pipeline Produces

The extractor normalizes each diagnostics archive into one offline analytics record with:

- `networkScopeKey` from the existing runtime network fingerprint scope
- connectivity assessment code and confidence
- control success and blocked-target failure summary
- resolver, HTTP, TLS, route-diversity, and trigger-fuzz summaries
- runtime component health summary
- normalized winner family and strategy-signature hash
- sparse interpretable feature set for clustering

The clustering stage groups similar records into offline device fingerprints. Each cluster records:

- stable cluster id and fingerprint hash
- support count
- dominant signals
- representative targets
- mined signature candidates
- stable winner families and signature hashes
- novelty and drift state against the current blessed baseline

The publish stage writes:

- `offline-records.json`
- `device-fingerprint-catalog.candidate.json`
- `device-fingerprint-winner-mappings.candidate.json`
- `drift-report.json`
- `report.md`

## Common Commands

Run the full pipeline on the checked-in sample corpus:

```bash
python3 -m scripts.analytics.pipeline run-all \
  --manifest scripts/analytics/sample-corpus.json \
  --output-dir /tmp/ripdpi-offline-analytics
```

Run extraction only:

```bash
python3 -m scripts.analytics.pipeline extract \
  --manifest scripts/analytics/sample-corpus.json \
  --output /tmp/ripdpi-offline-analytics/offline-records.json
```

Cluster an extracted dataset:

```bash
python3 -m scripts.analytics.pipeline cluster \
  --input /tmp/ripdpi-offline-analytics/offline-records.json \
  --output /tmp/ripdpi-offline-analytics/clustered-records.json
```

Publish candidate artifacts and analyst report:

```bash
python3 -m scripts.analytics.pipeline publish \
  --extracted /tmp/ripdpi-offline-analytics/offline-records.json \
  --clustered /tmp/ripdpi-offline-analytics/clustered-records.json \
  --output-dir /tmp/ripdpi-offline-analytics \
  --corpus-name local_archive_batch
```

Bless reviewed artifacts into repo assets:

```bash
python3 -m scripts.analytics.pipeline bless \
  --device-catalog /tmp/ripdpi-offline-analytics/device-fingerprint-catalog.candidate.json \
  --winner-catalog /tmp/ripdpi-offline-analytics/device-fingerprint-winner-mappings.candidate.json \
  --blessed-device-catalog app/src/main/assets/offline-analytics/device-fingerprint-catalog.json \
  --blessed-winner-catalog app/src/main/assets/offline-analytics/device-fingerprint-winner-mappings.json
```

## Input Expectations

For v1 the pipeline reads the existing diagnostics archive surface only:

- `manifest.json`
- `home-analysis.json` when present
- `analysis.json`
- `runtime-config.json`
- `diagnostic-context.json`
- `developer-analytics.json` when present
- `probe-results.csv`
- `telemetry.csv`

Stage-scoped `probe-results.csv` and `telemetry.csv` files under `stages/` are included automatically.

The extractor accepts either:

- a directory containing an unpacked diagnostics archive
- a diagnostics zip file

## Review And Blessing Workflow

1. Run the pipeline on the target corpus and inspect `report.md`.
2. Review cluster support counts, novelty, mined signatures, and winner mappings.
3. Compare `drift-report.json` against the currently blessed catalogs.
4. If the output is acceptable, bless the candidate artifacts into the repo assets.
5. Commit both the blessed JSON and any sample-corpus or test updates together.

The blessed asset pair is the only source of truth for reviewed offline device fingerprints. Candidate outputs are intentionally marked `unreviewed`.

## CI Coverage

Dedicated workflow:

- `.github/workflows/offline-analytics.yml`

It runs:

- `python3 -m unittest scripts.tests.test_offline_analytics_pipeline`
- the full sample-corpus pipeline
- optional private-corpus execution when a runner-local path is provided through `workflow_dispatch`

The main `ci.yml` build job also runs the offline analytics unit test so schema and pipeline regressions fail quickly on PRs.

## Privacy And Scope

The pipeline is derived from already-redacted diagnostics exports. It should not add any new raw endpoint disclosure beyond what the archive already permits.

Normalization rules intentionally:

- collapse raw endpoints into redacted labels where possible
- keep offline device fingerprints separate from runtime `NetworkFingerprint`
- treat reviewed catalogs as ranking/reporting inputs only until runtime consumption is explicitly implemented
