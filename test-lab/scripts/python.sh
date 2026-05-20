#!/usr/bin/env bash

ripdpi_resolve_python() {
  local purpose="${1:-test-lab checks}"

  if [[ -n "${RIPDPI_PYTHON_BIN:-}" ]]; then
    if [[ "$("$RIPDPI_PYTHON_BIN" -c 'print("ripdpi-python-ok")' 2>/dev/null)" == "ripdpi-python-ok" ]]; then
      echo "$RIPDPI_PYTHON_BIN"
      return 0
    fi
    echo "Configured RIPDPI_PYTHON_BIN is not a working Python interpreter: $RIPDPI_PYTHON_BIN" >&2
    return 1
  fi

  local candidate
  for candidate in /usr/bin/python3 python3; do
    if ! command -v "$candidate" >/dev/null 2>&1; then
      continue
    fi
    if [[ "$("$candidate" -c 'print("ripdpi-python-ok")' 2>/dev/null)" == "ripdpi-python-ok" ]]; then
      echo "$candidate"
      return 0
    fi
  done

  echo "No working Python interpreter found for $purpose." >&2
  return 1
}
