#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
: "${NETEM_STATE_DIR:?NETEM_STATE_DIR is required for transactional cleanup}"
[[ -s "$NETEM_STATE_DIR/session.env" ]] || {
  echo "No captured netem session exists at NETEM_STATE_DIR" >&2
  exit 1
}
netem_init_session
dev="$netem_dev"

delete_rule() {
  local family="$1"
  local check_status
  shift
  if "${netem_sudo[@]}" "$family" -C "$@" -m comment --comment "$netem_comment" 2>/dev/null; then
    "${netem_sudo[@]}" "$family" -D "$@" -m comment --comment "$netem_comment" || return 1
  else
    check_status=$?
    [[ $check_status -eq 1 ]] || return 1
    return 0
  fi
  if "${netem_sudo[@]}" "$family" -C "$@" -m comment --comment "$netem_comment" 2>/dev/null; then
    echo "Run-owned firewall rule remains after cleanup: $family $*" >&2
    return 1
  else
    check_status=$?
    [[ $check_status -eq 1 ]] || return 1
  fi
}

delete_nat_rule() {
  local check_status
  if "${netem_sudo[@]}" iptables -t nat -C POSTROUTING \
    -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE \
    -m comment --comment "$netem_comment" 2>/dev/null; then
    "${netem_sudo[@]}" iptables -t nat -D POSTROUTING \
      -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE \
      -m comment --comment "$netem_comment" || return 1
  else
    check_status=$?
    [[ $check_status -eq 1 ]] || return 1
    return 0
  fi
  if "${netem_sudo[@]}" iptables -t nat -C POSTROUTING \
    -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE \
    -m comment --comment "$netem_comment" 2>/dev/null; then
    echo "Run-owned NAT rule remains after cleanup" >&2
    return 1
  else
    check_status=$?
    [[ $check_status -eq 1 ]] || return 1
  fi
}

cleanup_failed=false
cleanup_rule() {
  if ! delete_rule "$@"; then
    echo "Failed to clean run-owned firewall rule: $*" >&2
    cleanup_failed=true
  fi
}

"${netem_sudo[@]}" tc qdisc del dev "$dev" root 2>/dev/null || true
while IFS= read -r rule_id; do
  case "$rule_id" in
    mtu-v4-output) cleanup_rule iptables OUTPUT -p icmp --icmp-type fragmentation-needed -j DROP ;;
    mtu-v4-forward) cleanup_rule iptables FORWARD -p icmp --icmp-type fragmentation-needed -j DROP ;;
    mtu-v6-output) cleanup_rule ip6tables OUTPUT -p ipv6-icmp --icmpv6-type packet-too-big -j DROP ;;
    mtu-v6-forward) cleanup_rule ip6tables FORWARD -p ipv6-icmp --icmpv6-type packet-too-big -j DROP ;;
    ipv6-hbh-output) cleanup_rule ip6tables OUTPUT -m hbh -j DROP ;;
    ipv6-hbh-forward) cleanup_rule ip6tables FORWARD -m hbh -j DROP ;;
    ipv6-dst-output) cleanup_rule ip6tables OUTPUT -m dst -j DROP ;;
    ipv6-dst-forward) cleanup_rule ip6tables FORWARD -m dst -j DROP ;;
    quic-input|quic-output)
      port="$(cat "$netem_state_dir/quic.port")"
      if [[ "$rule_id" == quic-input ]]; then
        cleanup_rule iptables INPUT -p udp --dport "$port" -j DROP
      else
        cleanup_rule iptables OUTPUT -p udp --sport "$port" -j DROP
      fi
      ;;
    carrier-nat)
      nat_subnet="$(sed -n '1p' "$netem_state_dir/nat.args")"
      nat_out_dev="$(sed -n '2p' "$netem_state_dir/nat.args")"
      [[ -n "$nat_subnet" && -n "$nat_out_dev" && "$(wc -l <"$netem_state_dir/nat.args" | tr -d ' ')" == "2" ]] || {
        echo "Captured netem NAT arguments are malformed" >&2
        cleanup_failed=true
        continue
      }
      if ! delete_nat_rule; then
        echo "Failed to clean run-owned NAT rule" >&2
        cleanup_failed=true
      fi
      ;;
    "") ;;
    *) echo "Unknown run-owned firewall rule id: $rule_id" >&2; cleanup_failed=true ;;
  esac
done <"$netem_state_dir/rules.applied"

if [[ -s "$netem_state_dir/qdisc.restore.args" ]]; then
  mapfile -t restore_args <"$netem_state_dir/qdisc.restore.args"
  if ! "${netem_sudo[@]}" tc qdisc replace dev "$dev" root "${restore_args[@]}"; then
    echo "Failed to restore the original root qdisc" >&2
    cleanup_failed=true
  fi
fi

if ! "${netem_sudo[@]}" ip link set dev "$dev" mtu "$NETEM_CAPTURED_MTU"; then
  echo "Failed to restore the original MTU" >&2
  cleanup_failed=true
fi
if ! "${netem_sudo[@]}" sysctl -w "net.ipv4.ip_forward=$NETEM_CAPTURED_IP_FORWARD" >/dev/null; then
  echo "Failed to restore IPv4 forwarding" >&2
  cleanup_failed=true
fi
if ! tc qdisc show dev "$dev" >"$netem_state_dir/qdisc.after"; then
  echo "Failed to capture the post-cleanup qdisc" >&2
  cleanup_failed=true
  : >"$netem_state_dir/qdisc.after"
fi
sed -E 's/ refcnt [0-9]+//' "$netem_state_dir/qdisc.before" >"$netem_state_dir/qdisc.before.normalized"
sed -E 's/ refcnt [0-9]+//' "$netem_state_dir/qdisc.after" >"$netem_state_dir/qdisc.after.normalized"
if ! cmp -s "$netem_state_dir/qdisc.before.normalized" "$netem_state_dir/qdisc.after.normalized"; then
  echo "Netem cleanup did not restore the complete original qdisc; recovery state retained at $netem_state_dir" >&2
  diff -u "$netem_state_dir/qdisc.before.normalized" "$netem_state_dir/qdisc.after.normalized" >&2 || true
  cleanup_failed=true
fi
if [[ "$cleanup_failed" == "true" ]]; then
  echo "Netem cleanup was incomplete; recovery state retained at $netem_state_dir" >&2
  exit 1
fi
rm -rf "$netem_state_dir"
