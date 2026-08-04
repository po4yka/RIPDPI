#!/usr/bin/env bash

netem_init_session() {
  : "${NETEM_STATE_DIR:?NETEM_STATE_DIR is required for transactional cleanup}"
  : "${NETEM_RUN_ID:?NETEM_RUN_ID is required for run-owned firewall rules}"
  if [[ ! "$NETEM_RUN_ID" =~ ^[A-Za-z0-9_.-]{1,64}$ ]]; then
    echo "NETEM_RUN_ID must contain 1..64 safe identifier characters" >&2
    return 2
  fi

  netem_dev="${NETEM_DEV:-eth0}"
  if [[ ! "$netem_dev" =~ ^[A-Za-z0-9_.:@-]+$ ]]; then
    echo "NETEM_DEV contains unsupported characters" >&2
    return 2
  fi
  netem_state_dir="$NETEM_STATE_DIR"
  netem_comment="ripdpi-netem-$NETEM_RUN_ID"
  netem_sudo=(sudo)
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    netem_sudo=()
  fi
  mkdir -p "$netem_state_dir"

  local metadata="$netem_state_dir/session.env"
  if [[ -f "$metadata" ]]; then
    NETEM_CAPTURED_DEV="$(sed -n 's/^NETEM_CAPTURED_DEV=//p' "$metadata")"
    NETEM_CAPTURED_RUN_ID="$(sed -n 's/^NETEM_CAPTURED_RUN_ID=//p' "$metadata")"
    NETEM_CAPTURED_MTU="$(sed -n 's/^NETEM_CAPTURED_MTU=//p' "$metadata")"
    NETEM_CAPTURED_IP_FORWARD="$(sed -n 's/^NETEM_CAPTURED_IP_FORWARD=//p' "$metadata")"
    [[ "$NETEM_CAPTURED_MTU" =~ ^[0-9]+$ && "$NETEM_CAPTURED_IP_FORWARD" =~ ^[01]$ ]] || {
      echo "Captured netem session metadata is malformed" >&2
      return 2
    }
    [[ "$NETEM_CAPTURED_DEV" == "$netem_dev" && "$NETEM_CAPTURED_RUN_ID" == "$NETEM_RUN_ID" ]] || {
      echo "NETEM_STATE_DIR belongs to a different device or run" >&2
      return 2
    }
    return 0
  fi

  local mtu ip_forward
  mtu="$(ip link show dev "$netem_dev" | awk '{for (i=1;i<=NF;i++) if ($i=="mtu") {print $(i+1); exit}}')"
  ip_forward="$(sysctl -n net.ipv4.ip_forward)"
  [[ "$mtu" =~ ^[0-9]+$ && "$ip_forward" =~ ^[01]$ ]] || {
    echo "Could not capture the network baseline" >&2
    return 1
  }
  tc qdisc show dev "$netem_dev" >"$netem_state_dir/qdisc.before"
  if grep -Eq '^qdisc netem ' "$netem_state_dir/qdisc.before"; then
    rm -f "$netem_state_dir/qdisc.before"
    echo "Refusing to layer a RIPDPI session over an existing netem qdisc" >&2
    return 1
  fi
  local qdisc_lines root_line kind handle qdisc_option index
  local -a qdisc_tokens
  qdisc_lines="$(grep -c '^qdisc ' "$netem_state_dir/qdisc.before" || true)"
  root_line="$(grep -E '^qdisc .* root([[:space:]]|$)' "$netem_state_dir/qdisc.before" || true)"
  if [[ "$qdisc_lines" != "1" || -z "$root_line" ]]; then
    rm -f "$netem_state_dir/qdisc.before"
    echo "Refusing netem over a qdisc hierarchy that cannot be restored exactly" >&2
    return 1
  fi
  read -r -a qdisc_tokens <<<"$root_line"
  kind="${qdisc_tokens[1]:-}"
  handle="${qdisc_tokens[2]:-}"
  if [[ ! "$kind" =~ ^[A-Za-z0-9_-]+$ || ! "$handle" =~ ^[A-Fa-f0-9]+:$ ]]; then
    echo "Unsupported root qdisc identity: $root_line" >&2
    return 1
  fi
  : >"$netem_state_dir/qdisc.restore.args"
  if [[ "$kind" != "noqueue" ]]; then
    if [[ "$handle" != "0:" ]]; then
      printf '%s\n' handle "$handle" >>"$netem_state_dir/qdisc.restore.args"
    fi
    printf '%s\n' "$kind" >>"$netem_state_dir/qdisc.restore.args"
    index=4
    while (( index < ${#qdisc_tokens[@]} )); do
      qdisc_option="${qdisc_tokens[index]}"
      if [[ "$qdisc_option" == "refcnt" ]]; then
        ((index += 2))
        continue
      fi
      if [[ ! "$qdisc_option" =~ ^[A-Za-z0-9_.:/%+-]+$ ]]; then
        echo "Unsupported root qdisc option; refusing a lossy restore: $qdisc_option" >&2
        return 1
      fi
      printf '%s\n' "$qdisc_option" >>"$netem_state_dir/qdisc.restore.args"
      ((index += 1))
    done
  fi
  cat >"$metadata" <<EOF
NETEM_CAPTURED_DEV=$netem_dev
NETEM_CAPTURED_RUN_ID=$NETEM_RUN_ID
NETEM_CAPTURED_MTU=$mtu
NETEM_CAPTURED_IP_FORWARD=$ip_forward
EOF
  NETEM_CAPTURED_DEV="$netem_dev"
  NETEM_CAPTURED_RUN_ID="$NETEM_RUN_ID"
  NETEM_CAPTURED_MTU="$mtu"
  NETEM_CAPTURED_IP_FORWARD="$ip_forward"
  : >"$netem_state_dir/rules.applied"
}

netem_mark_rule() {
  local rule_id="$1"
  grep -Fqx "$rule_id" "$netem_state_dir/rules.applied" 2>/dev/null ||
    printf '%s\n' "$rule_id" >>"$netem_state_dir/rules.applied"
}

netem_add_rule() {
  local rule_id="$1"
  local family="$2"
  shift 2
  if ! "${netem_sudo[@]}" "$family" -C "$@" -m comment --comment "$netem_comment" 2>/dev/null; then
    "${netem_sudo[@]}" "$family" -I "$@" -m comment --comment "$netem_comment"
  fi
  netem_mark_rule "$rule_id"
}
