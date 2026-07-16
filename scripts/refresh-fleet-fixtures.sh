#!/usr/bin/env bash
# Regenerate the fleet-compat fixtures from an exact ripdpi-vpn-deploy commit.
#
# The gate uses only frozen synthetic credentials and RFC-5737 addresses. It
# never reads live Terraform state or production SOPS data. Supported scenarios
# are emitted by the real deploy repository, validated by its bundle validator,
# compared byte-for-structure with the committed fixture, and then parsed by the
# JVM FleetCompat suite in .github/workflows/fleet-fixtures.yml.
#
# Usage:
#   RIPDPI_VPN_DEPLOY_DIR=/path/to/exact/checkout scripts/refresh-fleet-fixtures.sh --check
#   RIPDPI_VPN_DEPLOY_DIR=/path/to/exact/checkout scripts/refresh-fleet-fixtures.sh --write

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
fixtures_root="$repo_root/core/data/src/test/resources/fleet-fixtures"
frozen_secrets="$repo_root/scripts/fleet-fixtures/frozen-secrets.yaml"
pin_file="$repo_root/scripts/fleet-fixtures/deployer-git-sha.txt"
deployer_dir="${RIPDPI_VPN_DEPLOY_DIR:-$repo_root/../ripdpi-vpn-deploy}"

mode="check"
case "${1:-}" in
    --write) mode="write" ;;
    --check | "") mode="check" ;;
    *)
        echo "error: unknown argument: $1" >&2
        echo "usage: $0 [--check|--write]" >&2
        exit 1
        ;;
esac

die() {
    echo "error: $*" >&2
    exit 1
}

[[ -d "$fixtures_root" ]] || die "fixtures root not found: $fixtures_root"
[[ -f "$frozen_secrets" ]] || die "frozen secret-set not found: $frozen_secrets"
[[ -f "$pin_file" ]] || die "deployer pin not found: $pin_file"
command -v git >/dev/null 2>&1 || die "git is required"
command -v jq >/dev/null 2>&1 || die "jq is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

deployer_git_sha="$(tr -d '[:space:]' <"$pin_file")"
[[ "$deployer_git_sha" =~ ^[0-9a-f]{40}$ && "$deployer_git_sha" != "0000000000000000000000000000000000000000" ]] ||
    die "deployer pin must be one non-zero 40-character lowercase git SHA"

[[ -d "$deployer_dir/.git" || -f "$deployer_dir/.git" ]] ||
    die "deployer git checkout not found: $deployer_dir"
actual_deployer_sha="$(git -C "$deployer_dir" rev-parse HEAD 2>/dev/null)" ||
    die "cannot read deployer HEAD: $deployer_dir"
[[ "$actual_deployer_sha" == "$deployer_git_sha" ]] ||
    die "deployer checkout mismatch: expected $deployer_git_sha, got $actual_deployer_sha"
[[ -z "$(git -C "$deployer_dir" status --porcelain --untracked-files=all)" ]] ||
    die "deployer checkout must be clean at pinned commit $deployer_git_sha"

emitter="$deployer_dir/scripts/emit-bundle.sh"
validator="$deployer_dir/scripts/validate-bundle.py"
[[ -x "$emitter" ]] || die "deployer emitter missing or not executable: $emitter"
[[ -f "$validator" ]] || die "deployer validator missing: $validator"

scenarios=(
    p0-only
    p1-only
    p2a-hysteria-only
    p2a-hysteria-port-hop
    multi-cohort-p0-p1-p2a
    multi-host-failover
    per-app-bypass-and-via-tun
    bootstrap-bundle
)

refresh_meta_sha() {
    local scenario="$1"
    local meta="$fixtures_root/$scenario/meta.json"
    [[ -f "$meta" ]] || die "missing $scenario/meta.json"
    if [[ "$mode" == "write" ]]; then
        local updated
        updated="$(jq --arg sha "$deployer_git_sha" '.deployer_git_sha = $sha' "$meta")"
        printf '%s\n' "$updated" >"$meta"
    fi
}

work_dir="$(mktemp -d -t fleet-fixtures.XXXXXX)"
shim_dir="$work_dir/bin"
mkdir -p "$shim_dir"
cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT

# The real emitter only sees these deterministic shims. Terraform output is
# selected by provider name, SOPS always returns the frozen fixture payload,
# and wg derives a fixed synthetic public key without inspecting a live key.
cat >"$shim_dir/terraform" <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
chdir=""
args=()
for arg in "$@"; do
    case "$arg" in
        -chdir=*) chdir="${arg#-chdir=}" ;;
        *) args+=("$arg") ;;
    esac
done
provider="$(basename "$chdir")"
if [[ "${args[0]:-}" == "workspace" && "${args[1]:-}" == "select" ]]; then
    exit 0
fi
key="${args[2]:-}"
case "$provider:$key" in
    upcloud:server_ipv4) echo "203.0.113.10" ;;
    upcloud:server_hostname) echo "upcloud-prod-fixture" ;;
    hetzner:server_ipv4) echo "198.51.100.10" ;;
    hetzner:server_hostname) echo "hetzner-prod-fixture" ;;
    vultr:server_ipv4) echo "203.0.113.50" ;;
    vultr:server_hostname) echo "vultr-prod-fixture" ;;
    *:server_ipv4) echo "203.0.113.10" ;;
    *:server_hostname) echo "" ;;
    *) echo "fake-terraform: unsupported output: $provider $key" >&2; exit 1 ;;
esac
SHIM

cat >"$shim_dir/sops" <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
python3 - "$FLEET_FROZEN_SECRETS" <<'PY'
import json
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as handle:
    json.dump(yaml.safe_load(handle), sys.stdout)
PY
SHIM

cat >"$shim_dir/wg" <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "pubkey" ]] || { echo "fake-wg: only pubkey is supported" >&2; exit 1; }
cat >/dev/null
echo "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
SHIM

chmod +x "$shim_dir/terraform" "$shim_dir/sops" "$shim_dir/wg"
export FLEET_FROZEN_SECRETS="$frozen_secrets"

emit_bundle() {
    local scenario="$1"
    case "$scenario" in
        p0-only | bootstrap-bundle)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p0 \
                SOPS_FILE="$frozen_secrets" \
                "$emitter" laptop
            ;;
        p1-only)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p1-web \
                SOPS_FILE="$frozen_secrets" \
                "$emitter" laptop
            ;;
        p2a-hysteria-only)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p2-udp \
                SOPS_FILE="$frozen_secrets" \
                "$emitter" laptop
            ;;
        multi-cohort-p0-p1-p2a)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=device-full \
                SOPS_FILE="$frozen_secrets" \
                "$emitter" laptop
            ;;
        multi-host-failover)
            PATH="$shim_dir:$PATH" \
                HOSTS=upcloud:prod,hetzner:prod \
                COHORTS=p0-minimal,p0-minimal \
                SOPS_FILES="$frozen_secrets,$frozen_secrets" \
                "$emitter" laptop
            ;;
        per-app-bypass-and-via-tun)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p0 \
                SOPS_FILE="$frozen_secrets" \
                "$emitter" laptop \
                --per-app-bypass com.example.bankapp,com.example.localmedia \
                --per-app-via-tun com.example.browser
            ;;
        *)
            die "no emitter recipe for scenario: $scenario"
            ;;
    esac
}

drift=0
emitted=0
reference_only=0

for scenario in "${scenarios[@]}"; do
    scenario_dir="$fixtures_root/$scenario"
    [[ -d "$scenario_dir" ]] || die "missing scenario directory: $scenario"
    refresh_meta_sha "$scenario"

    case "$scenario" in
        p2a-hysteria-port-hop)
            echo "reference: $scenario is parser coverage not claimed as emitted provenance"
            reference_only=$((reference_only + 1))
            continue
            ;;
        p0-only | p1-only | p2a-hysteria-only | multi-cohort-p0-p1-p2a | multi-host-failover | per-app-bypass-and-via-tun | bootstrap-bundle) ;;
        *) die "unknown fleet fixture scenario: $scenario" ;;
    esac

    generated_file="$work_dir/$scenario.json"
    emitter_error="$work_dir/$scenario.stderr"
    set +e
    emit_bundle "$scenario" >"$generated_file" 2>"$emitter_error"
    emit_status=$?
    set -e

    if [[ $emit_status -ne 0 ]]; then
        echo "error: pinned emit-bundle.sh failed for $scenario" >&2
        sed -n '1,120p' "$emitter_error" >&2
        exit 1
    fi
    [[ -s "$generated_file" ]] || die "pinned emitter produced an empty bundle for $scenario"
    jq -e 'type == "object" and (.outbounds | type == "array" and length > 0) and (.ripdpi | type == "object")' \
        "$generated_file" >/dev/null || die "pinned emitter produced an incomplete bundle for $scenario"

    python3 "$validator" "$generated_file"

    committed="$(jq -S . "$scenario_dir/bundle.json")"
    regenerated="$(jq -S . "$generated_file")"
    if [[ "$mode" == "write" ]]; then
        jq . "$generated_file" >"$scenario_dir/bundle.json"
        echo "ok: wrote emitted and deploy-validated $scenario/bundle.json"
    elif [[ "$committed" != "$regenerated" ]]; then
        echo "drift: $scenario/bundle.json differs from pinned emitter output" >&2
        diff <(printf '%s\n' "$committed") <(printf '%s\n' "$regenerated") >&2 || true
        drift=1
    else
        echo "ok: $scenario/bundle.json matches pinned emitter and deploy validator"
    fi
    emitted=$((emitted + 1))
done

echo "==> running committed fixture provenance and structural checks"
python3 "$repo_root/scripts/ci/check_fleet_fixtures.py"

if [[ "$mode" == "write" ]]; then
    echo "done: regenerated $emitted emitted bundle(s), retained $reference_only explicit parser-reference scenario(s)"
    exit 0
fi
if [[ $drift -ne 0 ]]; then
    die "fixture drift detected; run '$0 --write' against the pinned clean deployer checkout"
fi

echo "ok: pinned deployer $deployer_git_sha emitted and validated $emitted bundle(s); generated structures match committed fixtures"
