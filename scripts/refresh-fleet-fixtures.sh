#!/usr/bin/env bash
# Regenerate the ripdpi-vpn-deploy fleet-compat golden fixtures under
#   core/data/src/test/resources/fleet-fixtures/<scenario>/bundle.json
# by running the sibling repo's scripts/emit-singbox.sh against a frozen,
# fully-synthetic secret-set -- no production tokens, no real infra.
#
# Usage:
#   scripts/refresh-fleet-fixtures.sh            # --check (default): diff vs committed
#   scripts/refresh-fleet-fixtures.sh --check    # explicit check mode
#   scripts/refresh-fleet-fixtures.sh --write    # overwrite the committed fixtures
#
# --check  regenerates each bundle.json into a temp dir and diffs it against
#          the committed copy; non-zero exit on drift. When the sibling repo
#          is absent or not at the pinned SHA, the emitter-diff portion is
#          skipped (CI has no sibling checkout) but the structural checks
#          still run via scripts/ci/check_fleet_fixtures.py.
# --write  overwrites the committed bundle.json files and refreshes each
#          meta.json's deployer_git_sha to the pin below. Use locally only.
#
# emit-singbox.sh needs `terraform` + `sops` + real infra, so this script
# shims both: a temp PATH dir provides a fake `terraform` (echoes the frozen
# RFC-5737 doc IPs / hostnames for `output -raw ...`) and a fake `sops` (emits
# the frozen-secrets file as JSON for `-d`). `jq` and `python3` are real.

set -euo pipefail

# --- The pinned deployer git SHA -------------------------------------------
# This is the deliberate operator knob: bumping it signals a deployer contract
# change. It MUST equal `deployer_git_sha` in every fixture meta.json, and
# scripts/ci/check_fleet_fixtures.py fails CI if the two drift apart.
DEPLOYER_GIT_SHA="0000000000000000000000000000000000000000-fixture"
# ---------------------------------------------------------------------------

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
fixtures_root="$repo_root/core/data/src/test/resources/fleet-fixtures"
frozen_secrets="$repo_root/scripts/fleet-fixtures/frozen-secrets.yaml"
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

if [[ ! -d "$fixtures_root" ]]; then
    echo "error: fixtures root not found: $fixtures_root" >&2
    exit 1
fi
if [[ ! -f "$frozen_secrets" ]]; then
    echo "error: frozen secret-set not found: $frozen_secrets" >&2
    exit 1
fi

# Scenarios the harness (FleetCompatGoldenFileTest) iterates.
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

# --- meta.json: always refresh the pinned SHA in place --------------------
# This is cheap, deployer-independent, and the drift signal the CI gate keys
# off, so it runs in both modes (in --check it would only change the file if
# the pin and the committed meta disagree, which the CI gate also catches).
refresh_meta_sha() {
    local scenario="$1"
    local meta="$fixtures_root/$scenario/meta.json"
    [[ -f "$meta" ]] || {
        echo "error: missing $scenario/meta.json" >&2
        return 1
    }
    local updated
    updated="$(jq --arg sha "$DEPLOYER_GIT_SHA" '.deployer_git_sha = $sha' "$meta")"
    if [[ "$mode" == "write" ]]; then
        printf '%s\n' "$updated" >"$meta"
    fi
}

# --- emitter shims ---------------------------------------------------------
# Build a temp PATH dir with fake `terraform` + `sops` so the real
# emit-singbox.sh runs with no infra. Both shims only ever echo frozen,
# synthetic values sourced from this repo.
emitter_available=false
shim_dir=""
cleanup() {
    [[ -n "$shim_dir" && -d "$shim_dir" ]] && rm -rf "$shim_dir"
}
trap cleanup EXIT

prepare_shims() {
    if [[ ! -d "$deployer_dir" ]]; then
        echo "note: sibling deployer repo not found at $deployer_dir" >&2
        echo "note: set RIPDPI_VPN_DEPLOY_DIR to enable emitter regeneration" >&2
        return 1
    fi
    if [[ ! -x "$deployer_dir/scripts/emit-singbox.sh" ]]; then
        echo "note: $deployer_dir/scripts/emit-singbox.sh missing or not executable" >&2
        return 1
    fi
    if ! command -v sops >/dev/null 2>&1 && ! command -v jq >/dev/null 2>&1; then
        echo "note: jq is required for the emitter path" >&2
        return 1
    fi

    shim_dir="$(mktemp -d -t fleet-fixtures.XXXXXX)"

    # Fake terraform: emit-singbox.sh calls `terraform -chdir=<dir> output -raw
    # server_ipv4` (and optionally server_hostname). Key the frozen value off
    # the provider directory name so multi-host scenarios get distinct IPs.
    cat >"$shim_dir/terraform" <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
chdir=""
args=()
for a in "$@"; do
    case "$a" in
        -chdir=*) chdir="${a#-chdir=}" ;;
        *) args+=("$a") ;;
    esac
done
provider="$(basename "$chdir")"
# args is: output -raw <key>
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

    # Fake sops: emit-singbox.sh calls `sops --decrypt --output-type json
    # <file>`. Convert the frozen-secrets YAML to JSON on stdout. The path
    # argument is ignored -- every host gets the same frozen secret-set.
    cat >"$shim_dir/sops" <<SHIM
#!/usr/bin/env bash
set -euo pipefail
python3 -c 'import json, sys, yaml; json.dump(yaml.safe_load(open("$frozen_secrets")), sys.stdout)'
SHIM

    chmod +x "$shim_dir/terraform" "$shim_dir/sops"
    emitter_available=true
    return 0
}

# Per-scenario emitter invocation. Echoes the bundle JSON on stdout, or
# returns non-zero (with a note on stderr) when the scenario needs deployer
# state this shim cannot synthesise.
#
# emit-singbox.sh checks the SOPS file physically exists before invoking
# `sops`, so SOPS_FILE points at the frozen-secrets file itself -- the fake
# `sops` shim ignores its path argument and always emits the frozen set.
emit_bundle() {
    local scenario="$1"
    local emit="$deployer_dir/scripts/emit-singbox.sh"
    case "$scenario" in
        p0-only)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p0 \
                SOPS_FILE="$frozen_secrets" \
                bash "$emit" laptop
            ;;
        p1-only)
            # P1-only cohort: xhttp on, reality off. The deployer's p1p2
            # cohort also enables hysteria, so a pure p1-only bundle needs a
            # cohort the sibling does not ship -- regenerate structurally only.
            return 2
            ;;
        per-app-bypass-and-via-tun)
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p0 \
                SOPS_FILE="$frozen_secrets" \
                bash "$emit" laptop \
                --per-app-bypass com.example.bankapp,com.example.localmedia \
                --per-app-via-tun com.example.browser
            ;;
        bootstrap-bundle)
            # Same content as p0-only, served one-shot; the bundle bytes are
            # identical to the p0-only emitter output.
            PATH="$shim_dir:$PATH" PROVIDER=upcloud ENV=prod COHORTS=p0 \
                SOPS_FILE="$frozen_secrets" \
                bash "$emit" laptop
            ;;
        p2a-hysteria-only | p2a-hysteria-port-hop | multi-cohort-p0-p1-p2a | multi-host-failover)
            # These need deployer state (hysteria-only cohort, port-hop range,
            # multi-cohort xray.cohorts, two-provider terraform state) that the
            # sibling repo does not check in. The committed fixtures stay
            # hand-authored; the structural CI gate still guards them.
            return 2
            ;;
        *)
            echo "error: no emitter recipe for scenario: $scenario" >&2
            return 1
            ;;
    esac
}

# --- main ------------------------------------------------------------------
drift=0
emitted=0
skipped=0

prepare_shims || true

for scenario in "${scenarios[@]}"; do
    scenario_dir="$fixtures_root/$scenario"
    if [[ ! -d "$scenario_dir" ]]; then
        echo "error: missing scenario directory: $scenario" >&2
        exit 1
    fi
    refresh_meta_sha "$scenario"

    if [[ "$emitter_available" != true ]]; then
        skipped=$((skipped + 1))
        continue
    fi

    set +e
    generated="$(emit_bundle "$scenario" 2>/tmp/fleet-fixtures-emit-err)"
    emit_status=$?
    set -e
    if [[ $emit_status -eq 2 ]]; then
        echo "note: $scenario -- structural-only (emitter needs unavailable deployer state)" >&2
        skipped=$((skipped + 1))
        continue
    fi
    if [[ $emit_status -ne 0 ]]; then
        echo "error: emit-singbox.sh failed for $scenario:" >&2
        cat /tmp/fleet-fixtures-emit-err >&2
        rm -f /tmp/fleet-fixtures-emit-err
        exit 1
    fi
    rm -f /tmp/fleet-fixtures-emit-err

    # Normalise both sides through jq so formatting differences don't show up
    # as drift -- only structural content matters.
    committed="$(jq -S . "$scenario_dir/bundle.json")"
    regenerated="$(jq -S . <<<"$generated")"

    if [[ "$mode" == "write" ]]; then
        jq . <<<"$generated" >"$scenario_dir/bundle.json"
        echo "ok: wrote $scenario/bundle.json"
        emitted=$((emitted + 1))
    else
        if [[ "$committed" != "$regenerated" ]]; then
            echo "drift: $scenario/bundle.json differs from emitter output" >&2
            diff <(echo "$committed") <(echo "$regenerated") >&2 || true
            drift=1
        else
            echo "ok: $scenario/bundle.json matches emitter output"
        fi
        emitted=$((emitted + 1))
    fi
done

# Structural checks always run -- this is the deployer-independent subset and
# the portion CI relies on.
echo "==> running structural fixture checks"
python3 "$repo_root/scripts/ci/check_fleet_fixtures.py"

if [[ "$mode" == "write" ]]; then
    echo "done: regenerated $emitted bundle(s), $skipped structural-only, meta.json SHAs pinned to $DEPLOYER_GIT_SHA"
    exit 0
fi

if [[ $drift -ne 0 ]]; then
    echo "error: fixture drift detected; run '$0 --write' to regenerate" >&2
    exit 1
fi

echo "ok: $emitted bundle(s) match the emitter, $skipped structural-only, no drift"
