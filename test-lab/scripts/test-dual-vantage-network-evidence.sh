#!/usr/bin/env bash
set -euo pipefail

development_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
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
source_checkout="$tmpdir/source-checkout"
git clone --quiet --shared "$development_root" "$source_checkout"
while IFS= read -r path; do
  [[ -n "$path" ]] || continue
  mkdir -p "$source_checkout/$(dirname "$path")"
  cp "$development_root/$path" "$source_checkout/$path"
done < <(
  {
    git -C "$development_root" diff --name-only
    git -C "$development_root" ls-files --others --exclude-standard
  } | sort -u
)
mutating_workload="$tmpdir/mutating-workload.py"
python3 - \
  "$source_checkout/test-lab/scripts/fixtures/network-evidence-fake-workload.py" \
  "$mutating_workload" <<'PY'
import sys

source_path, output_path = sys.argv[1:]
source = open(source_path, encoding="utf-8").read()
needle = "started = int(time.time()) - 1\n"
mutation = '''
for mutable_path in os.environ.get("RIPDPI_TEST_MUTATE_PATHS", "").split(os.pathsep):
    if mutable_path:
        Path(mutable_path).write_text("mutated after immutable snapshot\\n", encoding="utf-8")
'''
if source.count(needle) != 1:
    raise SystemExit("fake workload injection point changed")
with open(output_path, "w", encoding="utf-8") as handle:
    handle.write(source.replace(needle, mutation + needle))
PY
chmod 700 "$mutating_workload"
python3 - \
  "$source_checkout/quality/release-gates/network-evidence-producers.json" \
  "$source_checkout/test-lab/scripts/fixtures/network-evidence-fake-collector.py" \
  "$source_checkout/test-lab/scripts/fixtures/network-evidence-fake-workload.py" \
  "$mutating_workload" <<'PY'
import hashlib
import json
import sys

policy_path, collector_path, workload_path, mutating_workload_path = sys.argv[1:]
digest = lambda path: hashlib.sha256(open(path, "rb").read()).hexdigest()
with open(policy_path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "version": "network_evidence_producers_v2",
            "clientCollectorSha256": [digest(collector_path)],
            "observerCollectorSha256": [digest(collector_path)],
            "workloadSha256": [digest(workload_path), digest(mutating_workload_path)],
            "clientArtifactSha256": [
                hashlib.sha256(b"synthetic client artifact\n").hexdigest()
            ],
            "testArtifactSha256": [
                hashlib.sha256(b"synthetic test artifact\n").hexdigest()
            ],
        },
        handle,
        sort_keys=True,
    )
PY
git -C "$source_checkout" add -A
git -C "$source_checkout" \
  -c user.name=RIPDPI-Test -c user.email=test.invalid \
  commit --quiet -m "test fixture snapshot"
repo_root="$source_checkout"
runner="$repo_root/test-lab/scripts/run-dual-vantage-network-evidence.sh"
source_sha="$(git -C "$source_checkout" rev-parse HEAD)"
client_artifact="$tmpdir/client.apk"
printf 'synthetic client artifact\n' > "$client_artifact"
test_artifact="$tmpdir/test.apk"
printf 'synthetic test artifact\n' > "$test_artifact"
fake_bin="$tmpdir/fake-bin"
mkdir "$fake_bin"
python3 - "$fake_bin/adb" <<'PY'
import os
import sys

path = sys.argv[1]
script = r'''#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\nphysical-test\tdevice\n'
  exit 0
fi
[[ "${1:-}" == "-s" && "${2:-}" == "physical-test" ]]
shift 2
if [[ "${1:-}" == "shell" && "${2:-}" == "getprop" && "${3:-}" == "ro.kernel.qemu" ]]; then
  printf '0\n'
elif [[ "${1:-}" == "shell" && "${2:-}" == "getprop" && "${3:-}" == "ro.product.name" ]]; then
  printf 'pixel_physical\n'
elif [[ "${1:-}" == "shell" && "${2:-}" == "pm" && "${3:-}" == "path" ]]; then
  if [[ "${4:-}" == "com.poyka.ripdpi.test" ]]; then
    printf 'package:/data/app/ripdpi-test/base.apk\n'
  else
    printf 'package:/data/app/ripdpi/base.apk\n'
  fi
elif [[ "${1:-}" == "pull" ]]; then
  if [[ "$2" == */ripdpi-test/* ]]; then
    cp "$RIPDPI_TEST_INSTALLED_TEST_APK" "$3"
  else
    cp "$RIPDPI_TEST_INSTALLED_APK" "$3"
  fi
else
  exit 2
fi
'''
with open(path, "w", encoding="utf-8") as handle:
    handle.write(script)
os.chmod(path, 0o700)
PY
export PATH="$fake_bin:$PATH"
export RIPDPI_TEST_INSTALLED_APK="$client_artifact"
export RIPDPI_TEST_INSTALLED_TEST_APK="$test_artifact"
export RIPDPI_TEST_ALLOW_UNREADY_ACTIONS=1
local_execution_args=(
  --test-artifact "$test_artifact"
  --execution-kind local
  --execution-id local-self-test
  --execution-attempt 1
)

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
  bash "$runner" \
  --config "$config" \
  --output-dir "$output" \
  --source-sha "$source_sha" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" >/dev/null

[[ "$(find "$output" -maxdepth 1 -type f | wc -l | tr -d ' ')" == "3" ]]
[[ ! -e "$output/capture.pcap" ]]
python3 - "$output" "$client_artifact" "$test_artifact" "$runner" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

output = Path(sys.argv[1])
client_artifact = Path(sys.argv[2])
test_artifact = Path(sys.argv[3])
runner = Path(sys.argv[4])
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
assert manifest["provenance"]["executionKind"] == "local"
assert manifest["provenance"]["executionId"] == "local-self-test"
assert manifest["provenance"]["executionDefinition"] == "local"
assert manifest["provenance"]["clientArtifactSha256"] == hashlib.sha256(
    client_artifact.read_bytes()
).hexdigest()
assert manifest["provenance"]["testArtifactSha256"] == hashlib.sha256(
    test_artifact.read_bytes()
).hexdigest()
assert manifest["provenance"]["runnerSha256"] == hashlib.sha256(
    runner.read_bytes()
).hexdigest()
assert client["scenarioPlanSha256"] == observer["scenarioPlanSha256"]
assert manifest["provenance"]["scenarioPlanSha256"] == client["scenarioPlanSha256"]
assert len({window["actionMarkerSha256"] for window in client["windows"]}) == 10
assert len({window["outcomeMarkerSha256"] for window in client["windows"]}) == 10
assert len(manifest["gateResults"]) == 10
assert "dns-virtual-vpn-resolver" in manifest["gateResults"]
assert "killswitch-tun-establish-native-ready" in manifest["gateResults"]
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

if bash "$runner" \
  --config "$config" \
  --output-dir "$tmpdir/missing-test-artifact-output" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  --execution-kind local \
  --execution-id missing-test-artifact \
  --execution-attempt 1 >/dev/null 2>&1; then
  echo "missing test artifact unexpectedly produced evidence" >&2
  exit 1
fi
unapproved_test_artifact="$tmpdir/unapproved-test.apk"
printf 'unapproved synthetic test artifact\n' > "$unapproved_test_artifact"
if bash "$runner" \
  --config "$config" \
  --output-dir "$tmpdir/unapproved-test-artifact-output" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  --test-artifact "$unapproved_test_artifact" \
  --execution-kind local \
  --execution-id unapproved-test-artifact \
  --execution-attempt 1 >/dev/null 2>&1; then
  echo "unapproved test artifact unexpectedly produced evidence" >&2
  exit 1
fi

touch "$source_checkout/untracked-dirty-marker"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  bash "$runner" \
  --config "$config" \
  --output-dir "$tmpdir/dirty-source-output" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" >/dev/null 2>&1; then
  echo "dirty source checkout unexpectedly produced evidence" >&2
  exit 1
fi
rm "$source_checkout/untracked-dirty-marker"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  bash "$runner" \
  --config "$config" \
  --output-dir "$tmpdir/wrong-source-output" \
  --source-root "$source_checkout" \
  --source-sha aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" >/dev/null 2>&1; then
  echo "mismatched source SHA unexpectedly produced evidence" >&2
  exit 1
fi
different_installed_apk="$tmpdir/different-installed.apk"
printf 'different installed client\n' > "$different_installed_apk"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  RIPDPI_TEST_INSTALLED_APK="$different_installed_apk" \
  bash "$runner" \
  --config "$config" \
  --output-dir "$tmpdir/mismatched-installed-output" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" >/dev/null 2>&1; then
  echo "mismatched installed APK unexpectedly produced evidence" >&2
  exit 1
fi
different_installed_test_apk="$tmpdir/different-installed-test.apk"
printf 'different installed test client\n' > "$different_installed_test_apk"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  RIPDPI_TEST_INSTALLED_TEST_APK="$different_installed_test_apk" \
  bash "$runner" \
  --config "$config" \
  --output-dir "$tmpdir/mismatched-installed-test-output" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" >/dev/null 2>&1; then
  echo "mismatched installed test APK unexpectedly produced evidence" >&2
  exit 1
fi
results="$tmpdir/results.json"
python3 - "$repo_root/quality/release-gates/dns-ipv6-killswitch-gates.json" "$output/manifest.json" "$source_sha" "$results" <<'PY'
import json
import sys

policy_path, manifest_path, source_sha, output_path = sys.argv[1:]
with open(policy_path, encoding="utf-8") as handle:
    policy = json.load(handle)
with open(manifest_path, encoding="utf-8") as handle:
    evidence_gate_ids = set(json.load(handle)["gateResults"])
gate_results = {
    gate["id"]: {
        "state": "FAIL",
        "reason": (
            "SOURCE_OWNED_VERIFIER_UNAVAILABLE: checked-in raw-artifact "
            "verifier is not implemented; ordinary PASS is forbidden"
        ),
    }
    for gate in policy["gates"]
    if gate["id"] not in evidence_gate_ids
    and "android-client-release" in gate.get("appliesTo", policy["appliesTo"])
}
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "version": "dns_ipv6_killswitch_results_v1",
            "sourceSha": source_sha,
            "appliesTo": "android-client-release",
            "gateResults": gate_results,
        },
        handle,
    )
PY
if python3 "$repo_root/scripts/ci/check_dns_ipv6_killswitch_gates.py" \
  --results "$results" \
  --evidence-manifest "$output/manifest.json" \
  --applies-to android-client-release \
  --expected-source-sha "$source_sha" \
  --expected-execution-kind local \
  --expected-execution-id local-self-test \
  --expected-execution-attempt 1 >/dev/null 2>&1; then
  echo "fail-closed ordinary results unexpectedly passed release acceptance" >&2
  exit 1
fi
if python3 "$repo_root/scripts/ci/check_dns_ipv6_killswitch_gates.py" \
  --evidence-manifest "$output/manifest.json" \
  --applies-to android-client-release \
  --expected-source-sha "$source_sha" >/dev/null 2>&1; then
  echo "evidence-only gate check unexpectedly passed" >&2
  exit 1
fi
overlapping_results="$tmpdir/overlapping-results.json"
python3 - "$results" "$overlapping_results" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    results = json.load(handle)
results["gateResults"]["dns-virtual-vpn-resolver"] = "PASS"
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(results, handle)
PY
if python3 "$repo_root/scripts/ci/check_dns_ipv6_killswitch_gates.py" \
  --results "$overlapping_results" \
  --evidence-manifest "$output/manifest.json" \
  --applies-to android-client-release \
  --expected-source-sha "$source_sha" >/dev/null 2>&1; then
  echo "overlapping ordinary and dual-vantage results unexpectedly passed" >&2
  exit 1
fi

github_output="$tmpdir/github-output"
RIPDPI_TEST_REPO_ROOT="$repo_root" \
GITHUB_RUN_ID=42 \
GITHUB_RUN_ATTEMPT=1 \
  bash "$runner" \
  --config "$config" \
  --output-dir "$github_output" \
  --source-sha "$source_sha" \
  --source-root "$source_checkout" \
  --client-artifact "$client_artifact" \
  --test-artifact "$test_artifact" >/dev/null
python3 - "$github_output/manifest.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    provenance = json.load(handle)["provenance"]
assert provenance["executionKind"] == "github-actions"
assert provenance["executionId"] == "42"
assert provenance["executionAttempt"] == 1
assert provenance["executionDefinition"] == ".github/workflows/dns-ipv6-killswitch-evidence.yml"
PY

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
if bash "$runner" \
  --config "$bad_config" --output-dir "$bad_output" --source-sha "$source_sha" \
  --source-root "$source_checkout" --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" \
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
  if bash "$runner" \
    --config "$identity_config" --output-dir "$identity_output" --source-sha "$source_sha" \
    --source-root "$source_checkout" --client-artifact "$client_artifact" \
    "${local_execution_args[@]}" \
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
  bash "$runner" \
  --config "$config" --output-dir "$tmpdir/descendant-output" --source-sha "$source_sha" \
  --source-root "$source_checkout" --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" \
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
  bash "$runner" \
  --config "$config" --output-dir "$tmpdir/failure-output" --source-sha "$source_sha" \
  --source-root "$source_checkout" --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" \
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

workload_child_pid_file="$tmpdir/workload-child.pid"
if RIPDPI_TEST_REPO_ROOT="$repo_root" \
  RIPDPI_TEST_WORKLOAD_CHILD_PID_FILE="$workload_child_pid_file" \
  RIPDPI_TEST_WORKLOAD_HANG=1 \
  RIPDPI_TEST_WORKLOAD_TIMEOUT_SECONDS=1 \
  bash "$runner" \
  --config "$config" --output-dir "$tmpdir/workload-timeout-output" \
  --source-sha "$source_sha" --source-root "$source_checkout" \
  --client-artifact "$client_artifact" "${local_execution_args[@]}" \
  >/dev/null 2>&1; then
  echo "timed-out workload unexpectedly passed" >&2
  exit 1
fi
workload_child_pid="$(<"$workload_child_pid_file")"
for _ in {1..20}; do
  kill -0 "$workload_child_pid" 2>/dev/null || break
  sleep 0.1
done
if kill -0 "$workload_child_pid" 2>/dev/null; then
  echo "workload descendant survived process-group timeout cleanup" >&2
  exit 1
fi
[[ ! -e "$tmpdir/workload-timeout-output/manifest.json" ]]

unapproved_workload="$tmpdir/unapproved-workload.py"
cp "$workload" "$unapproved_workload"
printf '\n# unapproved producer mutation\n' >> "$unapproved_workload"
chmod 700 "$unapproved_workload"
unapproved_config="$tmpdir/unapproved-runner.json"
python3 - "$config" "$unapproved_config" "$unapproved_workload" <<'PY'
import json
import sys

source_path, output_path, workload_path = sys.argv[1:]
with open(source_path, encoding="utf-8") as handle:
    config = json.load(handle)
config["workloadHook"] = workload_path
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(config, handle)
PY
chmod 600 "$unapproved_config"
if bash "$runner" \
  --config "$unapproved_config" --output-dir "$tmpdir/unapproved-output" \
  --source-sha "$source_sha" --source-root "$source_checkout" \
  --client-artifact "$client_artifact" "${local_execution_args[@]}" \
  >/dev/null 2>&1; then
  echo "unapproved workload unexpectedly produced evidence" >&2
  exit 1
fi
[[ ! -e "$tmpdir/unapproved-output/manifest.json" ]]

caller_snapshot_root="$tmpdir/caller-controlled-snapshot"
mkdir "$caller_snapshot_root"
touch "$caller_snapshot_root/must-survive"
RIPDPI_TEST_REPO_ROOT="$repo_root" \
RIPDPI_EVIDENCE_SNAPSHOT_ACTIVE=1 \
RIPDPI_EVIDENCE_SNAPSHOT_ROOT="$caller_snapshot_root" \
  bash "$runner" \
  --config "$config" --output-dir "$tmpdir/caller-sentinel-output" \
  --source-sha "$source_sha" --source-root "$source_checkout" \
  --client-artifact "$client_artifact" "${local_execution_args[@]}" >/dev/null
[[ -e "$caller_snapshot_root/must-survive" ]]

mutation_config="$tmpdir/mutation-runner.json"
python3 - "$config" "$mutation_config" "$mutating_workload" <<'PY'
import json
import sys

source_path, output_path, workload_path = sys.argv[1:]
with open(source_path, encoding="utf-8") as handle:
    config = json.load(handle)
config["workloadHook"] = workload_path
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(config, handle)
PY
chmod 600 "$mutation_config"
expected_collector_sha256="$(shasum -a 256 "$client_collector" | awk '{print $1}')"
expected_workload_sha256="$(shasum -a 256 "$mutating_workload" | awk '{print $1}')"
mutation_output="$tmpdir/mutation-output"
mutation_paths="$runner:$repo_root/scripts/ci/network_evidence_manifest.py"
mutation_paths+=":$repo_root/quality/release-gates/dns-ipv6-killswitch-gates.json"
mutation_paths+=":$repo_root/quality/release-gates/network-evidence-producers.json"
mutation_paths+=":$mutation_config:$client_collector:$observer_collector:$mutating_workload"
RIPDPI_TEST_REPO_ROOT="$repo_root" \
RIPDPI_TEST_MUTATE_PATHS="$mutation_paths" \
  bash "$runner" \
  --config "$mutation_config" --output-dir "$mutation_output" --source-sha "$source_sha" \
  --source-root "$source_checkout" --client-artifact "$client_artifact" \
  "${local_execution_args[@]}" >/dev/null
python3 - \
  "$mutation_output/manifest.json" "$source_checkout" "$source_sha" \
  "$expected_collector_sha256" "$expected_workload_sha256" <<'PY'
import hashlib
import json
import subprocess
import sys

manifest_path, source_root, source_sha, collector_sha, workload_sha = sys.argv[1:]
with open(manifest_path, encoding="utf-8") as handle:
    manifest = json.load(handle)
provenance = manifest["provenance"]

def committed_digest(path: str) -> str:
    payload = subprocess.run(
        ["git", "-C", source_root, "show", f"{source_sha}:{path}"],
        check=True,
        capture_output=True,
    ).stdout
    return hashlib.sha256(payload).hexdigest()

assert provenance["runnerSha256"] == committed_digest(
    "test-lab/scripts/run-dual-vantage-network-evidence.sh"
)
assert provenance["validatorSha256"] == committed_digest(
    "scripts/ci/network_evidence_manifest.py"
)
assert provenance["policySha256"] == committed_digest(
    "quality/release-gates/dns-ipv6-killswitch-gates.json"
)
assert provenance["producerPolicySha256"] == committed_digest(
    "quality/release-gates/network-evidence-producers.json"
)
assert provenance["workloadSha256"] == workload_sha
assert {artifact["collectorSha256"] for artifact in manifest["artifacts"]} == {
    collector_sha
}
PY

echo "Dual-vantage network evidence runner self-test passed."
