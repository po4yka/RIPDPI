#!/usr/bin/env bash

remove_generated_corefile_active() {
  local corefile="$lab_root/dns/Corefile.active"
  if [[ -d "$corefile" && ! -L "$corefile" ]]; then
    if rmdir "$corefile" 2>/dev/null; then
      return 0
    fi
    echo "Refusing to remove non-empty generated Corefile.active directory: $corefile" >&2
    return 1
  fi
  rm -f "$corefile"
}
