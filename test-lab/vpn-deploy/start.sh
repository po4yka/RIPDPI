#!/usr/bin/env bash
# Bring up the sibling ripdpi-vpn-deploy full-stack-published scenario.
#
# Idempotent: re-running `start.sh` on a live container is a no-op
# (molecule converge skips fully-converged tasks). Use stop.sh to
# destroy.

set -euo pipefail

ripdpi_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
deploy_dir="${RIPDPI_VPN_DEPLOY_DIR:-${HOME}/GitHub/ripdpi-vpn-deploy}"
venv_dir="${ripdpi_root}/test-lab/vpn-deploy/.venv"
scenario="full-stack-published"
container="vpn-fullstack-debian13-published"

err() { printf 'error: %s\n' "$*" >&2; exit 1; }
log() { printf '[vpn-deploy] %s\n' "$*"; }

if ! [ -d "${deploy_dir}/ansible/molecule/${scenario}" ]; then
  err "scenario ${scenario} not found at ${deploy_dir}/ansible/molecule/${scenario}; \
set RIPDPI_VPN_DEPLOY_DIR or clone the sibling repo"
fi

if ! command -v docker >/dev/null 2>&1; then
  err "docker not on PATH"
fi
if ! docker info >/dev/null 2>&1; then
  err "docker daemon not reachable"
fi

case "${1:-up}" in
  --status|status)
    if docker ps --filter "name=${container}" --format '{{.Names}}' | grep -q "${container}$"; then
      docker port "${container}" | sed 's/^/  /'
      docker exec "${container}" systemctl list-units --type=service --state=running --no-legend 2>/dev/null \
        | awk '{ print "  service:", $1 }'
    else
      echo "container ${container} not running"
      exit 1
    fi
    exit 0
    ;;
  --help|-h|help)
    grep -E '^#' "${BASH_SOURCE[0]}" | head -10
    exit 0
    ;;
esac

# 1. Provision a venv with the deploy repo's pinned Python deps.
#    The deploy repo's mise.toml pins python = "3.12" and the requirements
#    file was compiled with --python-version 3.12; resolve a 3.12 interpreter
#    explicitly rather than relying on whatever `python3` is on PATH.
py_bin=""
for candidate in \
    "${RIPDPI_VPN_DEPLOY_PYTHON:-}" \
    "$(command -v python3.12 || true)" \
    "/opt/homebrew/bin/python3.12" \
    "/usr/local/bin/python3.12"; do
  [ -n "${candidate}" ] && [ -x "${candidate}" ] || continue
  ver="$("${candidate}" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
  case "${ver}" in
    3.12|3.13|3.14) py_bin="${candidate}"; break ;;
  esac
done
[ -n "${py_bin}" ] || err "no Python >= 3.12 found; install python@3.12 (brew install python@3.12) or set RIPDPI_VPN_DEPLOY_PYTHON"

if [ ! -x "${venv_dir}/bin/python3" ] || ! "${venv_dir}/bin/python3" -c 'import sys; assert sys.version_info[:2] >= (3,12)' 2>/dev/null; then
  log "creating venv at ${venv_dir} with $(${py_bin} --version)"
  rm -rf "${venv_dir}"
  "${py_bin}" -m venv "${venv_dir}"
fi
if [ ! -f "${venv_dir}/.requirements.sha" ] \
   || ! diff -q "${venv_dir}/.requirements.sha" "${deploy_dir}/requirements.txt" >/dev/null 2>&1; then
  log "installing python deps from ${deploy_dir}/requirements.txt"
  "${venv_dir}/bin/pip" install --quiet --upgrade pip
  "${venv_dir}/bin/pip" install --quiet --require-hashes -r "${deploy_dir}/requirements.txt"
  cp "${deploy_dir}/requirements.txt" "${venv_dir}/.requirements.sha"
fi

# 2. Install Ansible Galaxy collections referenced by the playbooks.
log "ensuring ansible-galaxy collections"
ANSIBLE_COLLECTIONS_PATH="${venv_dir}/share/ansible-collections" \
  "${venv_dir}/bin/ansible-galaxy" collection install \
    -r "${deploy_dir}/requirements.yml" >/dev/null

# 3. Run molecule converge against the published-ports scenario.
log "running molecule converge for scenario '${scenario}'"
cd "${deploy_dir}/ansible"
ANSIBLE_COLLECTIONS_PATH="${venv_dir}/share/ansible-collections" \
PATH="${venv_dir}/bin:${PATH}" \
  molecule converge -s "${scenario}"

# 4. Defensive: a failed earlier converge can leave /var/log/xray/*.log
#    owned root:root, after which xray (running as user xray) crash-loops
#    with "permission denied". Fix in place and bounce the unit if needed.
log "ensuring xray log files are writable by the xray user"
docker exec "${container}" bash -c '
  if [ -d /var/log/xray ]; then
    chown xray:xray /var/log/xray/*.log 2>/dev/null || true
    chmod 0640 /var/log/xray/*.log 2>/dev/null || true
    systemctl reset-failed xray 2>/dev/null || true
    systemctl is-active --quiet xray || systemctl restart xray 2>/dev/null || true
  fi
' >/dev/null 2>&1 || true

# 5. Report endpoints.
log "converge complete; published endpoints:"
docker port "${container}" | sed 's/^/  /'

log "service health:"
docker exec "${container}" systemctl is-active xray hysteria-server nginx 2>/dev/null \
  | paste - - - \
  | awk '{print "  xray=" $1 "  hysteria-server=" $2 "  nginx=" $3}'

log "to tear down: test-lab/vpn-deploy/stop.sh"
