#!/usr/bin/env bash
# Tear down the full-stack-published scenario container and any
# molecule state for it. Idempotent.

set -euo pipefail

ripdpi_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
deploy_dir="${RIPDPI_VPN_DEPLOY_DIR:-${HOME}/GitHub/ripdpi-vpn-deploy}"
venv_dir="${ripdpi_root}/test-lab/vpn-deploy/.venv"
scenario="full-stack-published"

if [ -x "${venv_dir}/bin/molecule" ] && [ -d "${deploy_dir}/ansible/molecule/${scenario}" ]; then
  cd "${deploy_dir}/ansible"
  PATH="${venv_dir}/bin:${PATH}" molecule destroy -s "${scenario}" || true
fi

# Belt-and-braces: molecule destroy uses the docker driver; if the venv
# is broken, still try to remove the container directly.
docker rm -f "vpn-fullstack-debian13-published" >/dev/null 2>&1 || true

echo "[vpn-deploy] torn down"
