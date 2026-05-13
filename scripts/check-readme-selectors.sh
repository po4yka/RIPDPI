#!/usr/bin/env bash
# Verify README selector-block parity across all 7 README files (per plan i18n-es-de-fr.md v3.1 § J #10).
# Asserts:
#   (a) Each README links to the other 6 (42 link assertions total)
#   (b) Each README has exactly one <b>...</b> wrapping its own native locale name
# Exit codes: 0 = pass, 1 = fail.
set -euo pipefail

cd "$(dirname "$0")/.."

# Map: README file path -> expected native locale name + link target basename
declare -A NATIVE_NAME=(
  ["README.md"]="English"
  ["README-ru.md"]="Русский"
  ["README-es.md"]="Español"
  ["README-de.md"]="Deutsch"
  ["README-fr.md"]="Français"
  ["README-zh-CN.md"]="简体中文"
  ["docs/fa/README.md"]="فارسی"
)

declare -A LINK_TARGET=(
  ["README.md"]="README.md"
  ["README-ru.md"]="README-ru.md"
  ["README-es.md"]="README-es.md"
  ["README-de.md"]="README-de.md"
  ["README-fr.md"]="README-fr.md"
  ["README-zh-CN.md"]="README-zh-CN.md"
  ["docs/fa/README.md"]="docs/fa/README.md"
)

errors=0

for self in "${!NATIVE_NAME[@]}"; do
  # Extract the selector block: first <p align="center"> line containing a '|' delimiter
  block=$(awk '/<p align="center">.*\|.*<\/p>/ { print; exit }' "$self")
  if [[ -z "$block" ]]; then
    echo "FAIL: $self has no selector block matching <p align=\"center\">...|...</p>"
    errors=$((errors + 1))
    continue
  fi

  # (a) Link assertions: this file must reference the basename of each OTHER file
  for other in "${!LINK_TARGET[@]}"; do
    [[ "$other" == "$self" ]] && continue
    target="${LINK_TARGET[$other]}"
    target_basename=$(basename "$target")
    # The link inside selector blocks uses either basename (for top-level) or ../../basename (from docs/fa/)
    if ! echo "$block" | grep -qF "$target_basename"; then
      echo "FAIL: $self selector block missing link to $target_basename"
      errors=$((errors + 1))
    fi
  done

  # (b) Bold-tag assertion: exactly one <b>...</b> in block, matching self's native name
  bold_count=$(echo "$block" | grep -oE '<b>[^<]+</b>' | wc -l | tr -d ' ')
  if [[ "$bold_count" != "1" ]]; then
    echo "FAIL: $self selector block has $bold_count <b> tags, expected 1"
    errors=$((errors + 1))
    continue
  fi
  bold_actual=$(echo "$block" | grep -oE '<b>[^<]+</b>' | head -1)
  expected="<b>${NATIVE_NAME[$self]}</b>"
  if [[ "$bold_actual" != "$expected" ]]; then
    echo "FAIL: $self selector bolded '$bold_actual', expected '$expected'"
    errors=$((errors + 1))
  fi
done

if [[ "$errors" -gt 0 ]]; then
  echo ""
  echo "$errors selector-block assertion(s) failed."
  exit 1
fi

echo "All 7 README selector blocks pass parity + bold-tag assertions (42 link + 7 bold assertions)."
