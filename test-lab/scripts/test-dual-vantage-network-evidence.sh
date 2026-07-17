#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/test-lab/scripts/run-dual-vantage-network-evidence.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-dual-vantage-test.XXXXXX")"
cleanup_test() {
  for pid_file in "$tmpdir"/*.pid; do
    if [[ -f "$pid_file" ]]; then
      kill "$(<"$pid_file")" 2>/dev/null || true
    fi
  done
  rm -rf "$tmpdir"
}
trap cleanup_test EXIT

client_collector="$tmpdir/client-collector.py"
observer_collector="$tmpdir/observer-collector.py"
workload="$tmpdir/workload.py"
config="$tmpdir/runner.json"
output="$tmpdir/output"
source_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
client_artifact_sha256="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

cp "$repo_root/test-lab/scripts/fixtures/network-evidence-fake-collector.py" "$client_collector"
cp "$repo_root/test-lab/scripts/fixtures/network-evidence-fake-collector.py" "$observer_collector"
cp "$repo_root/test-lab/scripts/fixtures/network-evidence-fake-workload.py" "$workload"
chmod 700 "$client_collector" "$observer_collector" "$workload"
python3 - "$config" "$client_collector" "$observer_collector" "$workload" <<'PY'
import json
import sys

path, client_collector, observer_collector, workload = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "version": "ripdpi_network_evidence_runner_v1",
            "clientHook": client_collector,
            "observerHook": observer_collector,
            "workloadHook": workload,
            "clientVantageId": "1" * 64,
            "observerVantageId": "2" * 64,
            "clientNetworkId": "3" * 64,
            "observerNetworkId": "4" * 64,
        },
        handle,
    )
PY
chmod 600 "$config"

RIPDPI_TEST_REPO_ROOT="$repo_root" \
GITHUB_RUN_ID=42 \
GITHUB_RUN_ATTEMPT=1 \
  bash "$runner" \
  --config "$config" \
  --output-dir "$output" \
  --source-sha "$source_sha" \
  --client-artifact-sha256 "$client_artifact_sha256" >/dev/null

[[ "$(find "$output" -maxdepth 1 -type f | wc -l | tr -d ' ')" == "3" ]]
[[ ! -e "$output/capture.pcap" ]]
python3 - "$output" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

output = Path(sys.argv[1])
documents = {
    path.name: json.loads(path.read_text(encoding="utf-8"))
    for path in output.glob("*.json")
}
client_vantage_id = "1" * 64
observer_vantage_id = "2" * 64
client_network_id = "3" * 64
observer_network_id = "4" * 64
client_network_digest = hashlib.sha256(
    f"ripdpi:network:v1:{client_network_id}".encode()
).hexdigest()
observer_network_digest = hashlib.sha256(
    f"ripdpi:network:v1:{observer_network_id}".encode()
).hexdigest()
client_vantage_digest = hashlib.sha256(
    f"ripdpi:vantage:v1:{client_vantage_id}".encode()
).hexdigest()
observer_vantage_digest = hashlib.sha256(
    f"ripdpi:vantage:v1:{observer_vantage_id}".encode()
).hexdigest()

client = documents["client-observation.json"]
observer = documents["observer-observation.json"]
manifest = documents["manifest.json"]
assert client["networkIdSha256"] == client_network_digest
assert observer["networkIdSha256"] == observer_network_digest
assert client["vantageIdSha256"] == client_vantage_digest
assert observer["vantageIdSha256"] == observer_vantage_digest
assert client_network_digest != client_vantage_digest
assert observer_network_digest != observer_vantage_digest
assert [artifact["networkIdSha256"] for artifact in manifest["artifacts"]] == [
    client_network_digest,
    observer_network_digest,
]
published = b"".join(path.read_bytes() for path in output.glob("*.json"))
for raw_identity in (
    client_vantage_id,
    observer_vantage_id,
    client_network_id,
    observer_network_id,
):
    assert raw_identity.encode() not in published
assert b"clientNetworkId" not in published
assert b"observerNetworkId" not in published
PY
python3 "$repo_root/scripts/ci/check_dns_ipv6_killswitch_gates.py" \
  --evidence-manifest "$output/manifest.json" \
  --applies-to android-client-release \
  --expected-source-sha "$source_sha" \
  --expected-evidence-run-id 42 \
  --expected-evidence-run-attempt 1 >/dev/null

bad_config="$tmpdir/bad-runner.json"
python3 - "$bad_config" "$client_collector" "$observer_collector" <<'PY'
import json
import sys
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump(
        {
            "version": "ripdpi_network_evidence_runner_v1",
            "clientHook": sys.argv[2],
            "observerHook": sys.argv[3],
            "workloadHook": "/does/not/exist",
            "clientVantageId": "1" * 64,
            "observerVantageId": "2" * 64,
            "clientNetworkId": "3" * 64,
            "observerNetworkId": "4" * 64,
        },
        handle,
    )
PY
chmod 600 "$bad_config"
bad_output="$tmpdir/bad-output"
mkdir -m 700 "$bad_output"
for name in client-observation.json observer-observation.json manifest.json; do
  printf 'SYNTHETIC_STALE_BUNDLE\n' > "$bad_output/$name"
done
if GITHUB_RUN_ID=43 GITHUB_RUN_ATTEMPT=1 \
  bash "$runner" \
  --config "$bad_config" --output-dir "$bad_output" --source-sha "$source_sha" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  >/dev/null 2>&1; then
  echo "invalid private runner config unexpectedly passed" >&2
  exit 1
fi
if [[ -n "$(find "$bad_output" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "early runner failure left a publishable stale bundle" >&2
  exit 1
fi

for mutation in missing duplicate cross-type; do
  identity_config="$tmpdir/${mutation}-network-runner.json"
  python3 - "$identity_config" "$config" "$mutation" <<'PY'
import json
import sys

output_path, source_path, mutation = sys.argv[1:]
with open(source_path, encoding="utf-8") as handle:
    config = json.load(handle)
if mutation == "missing":
    config.pop("observerNetworkId")
elif mutation == "duplicate":
    config["observerNetworkId"] = config["clientNetworkId"]
else:
    config["clientNetworkId"] = config["clientVantageId"]
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(config, handle)
PY
  chmod 600 "$identity_config"
  identity_output="$tmpdir/${mutation}-network-output"
  if GITHUB_RUN_ID=46 GITHUB_RUN_ATTEMPT=1 \
    bash "$runner" \
    --config "$identity_config" --output-dir "$identity_output" --source-sha "$source_sha" \
    --client-artifact-sha256 "$client_artifact_sha256" \
    >/dev/null 2>&1; then
    echo "$mutation network identity config unexpectedly passed" >&2
    exit 1
  fi
  if [[ -n "$(find "$identity_output" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "$mutation network identity failure left a publishable bundle" >&2
    exit 1
  fi
done

descendant_pid_file="$tmpdir/descendant.pid"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  RIPDPI_TEST_CHILD_PID_FILE="$descendant_pid_file" \
  GITHUB_RUN_ID=45 GITHUB_RUN_ATTEMPT=1 \
  bash "$runner" \
  --config "$config" --output-dir "$tmpdir/descendant-output" --source-sha "$source_sha" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  >/dev/null 2>&1; then
  echo "collector descendant unexpectedly produced publishable evidence" >&2
  exit 1
fi
descendant_pid="$(<"$descendant_pid_file")"
for _ in {1..20}; do
  kill -0 "$descendant_pid" 2>/dev/null || break
  sleep 0.1
done
if kill -0 "$descendant_pid" 2>/dev/null; then
  echo "collector descendant survived fail-closed cleanup" >&2
  exit 1
fi
[[ ! -e "$tmpdir/descendant-output/manifest.json" ]]

failure_child_pid="$tmpdir/failure-child.pid"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  RIPDPI_TEST_CHILD_PID_FILE="$failure_child_pid" \
  RIPDPI_TEST_WORKLOAD_FAIL=1 \
  GITHUB_RUN_ID=44 GITHUB_RUN_ATTEMPT=1 \
  bash "$runner" \
  --config "$config" --output-dir "$tmpdir/failure-output" --source-sha "$source_sha" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  >/dev/null 2>&1; then
  echo "failing workload unexpectedly passed" >&2
  exit 1
fi
failure_child="$(<"$failure_child_pid")"
for _ in {1..20}; do
  kill -0 "$failure_child" 2>/dev/null || break
  sleep 0.1
done
if kill -0 "$failure_child" 2>/dev/null; then
  echo "collector child survived failure cleanup" >&2
  exit 1
fi
[[ ! -e "$tmpdir/failure-output/manifest.json" ]]

echo "Dual-vantage network evidence runner self-test passed."
