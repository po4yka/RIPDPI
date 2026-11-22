#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir/../.."

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-raw-https-e2e.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
control_port="${RIPDPI_FIXTURE_CONTROL_PORT:-46090}"
android_host="${RIPDPI_FIXTURE_ANDROID_HOST:-10.0.2.2}"
test_class="com.poyka.ripdpi.e2e.DiagnosticsNetworkE2ETest"
tests=(
  "$test_class#manualRawVolumeComparisonPersistsVerifiedShortAndStalledLongTransfers"
  "$test_class#manualRawHttp3ComparisonPersistsBodyDivergence"
)
test_filter="$(IFS=,; printf '%s' "${tests[*]}")"
results_dir="app/build/outputs/androidTest-results/connected/debug/flavors/github"

curl --fail --silent --show-error --max-time 5 "http://127.0.0.1:$control_port/manifest" |
  jq -er '.tlsCertificatePem' |
  openssl x509 -outform DER -out "$work_dir/fixture-root.der"

bash "$script_dir/wait-for-android-package-manager.sh"
printf '%s\n' "$test_filter" | tee "${RUNNER_TEMP:-/tmp}/android-instrumented-target.txt"
rm -rf -- "$results_dir"
./gradlew :app:connectedGithubDebugAndroidTest \
  "$@" \
  "-Pripdpi.instrumentedDiagnosticsRoot=$work_dir/fixture-root.der" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$test_filter" \
  "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=$android_host" \
  "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=$control_port" \
  -Pandroid.testInstrumentationRunnerArguments.ripdpi.verifiedHttpsFixtureTrust=true \
  -Pandroid.testInstrumentationRunnerArguments.coverage=false

for test_name in "${tests[@]}"; do
  python3 "$script_dir/validate_android_junit_results.py" "$results_dir" \
    --require-test "$test_name" --expected-count 1 --expected-total-count "${#tests[@]}" --forbid-skips
done
