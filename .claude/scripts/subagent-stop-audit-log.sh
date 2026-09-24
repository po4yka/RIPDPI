#!/usr/bin/env bash
# subagent-stop-audit-log.sh — SubagentStop hook (Claude Code v2.1.145+)
#
# Logs every sub-agent finish to .claude/logs/subagent-stop.log so the operator
# can audit what each spawned sub-agent actually produced. Adds a WARNING line
# when an audit-class sub-agent (unsafe-code-auditor, jni-bridge-verifier,
# async-cancel-safety, arch-layer-auditor, rust-api-auditor, kotlin-design-auditor)
# returns a final message that does NOT contain a recognized conclusion marker
# (e.g., "findings", "audit complete", "0 violations", "RISK"), which usually
# signals a degenerate run.
#
# Pure observability by design: it never blocks, continues, or feeds context back
# into the sub-agent (both Claude Code and Codex would treat decision/exit 2 as
# "keep the sub-agent running"), so it always exits 0 with no stdout.
#
# Disabled with: RIPDPI_SUBAGENT_HOOKS=off

set -uo pipefail

[[ "${RIPDPI_SUBAGENT_HOOKS:-on}" == "off" ]] && exit 0

# stdin: hook input JSON.
# Top-level fields available on SubagentStop:
#   agent_id, agent_type, agent_transcript_path, last_assistant_message,
#   session_id, hook_event_name
input=$(cat 2>/dev/null) || exit 0

agent_type=$(echo "$input" | jq -r '.agent_type // empty')
agent_id=$(echo "$input" | jq -r '.agent_id // empty')
last_msg=$(echo "$input" | jq -r '.last_assistant_message // empty')

[[ -z "$agent_type" ]] && exit 0

root=$(git -c core.fsmonitor=false rev-parse --show-toplevel 2>/dev/null || pwd)
log_dir="$root/.claude/logs"
mkdir -p "$log_dir"
log_file="$log_dir/subagent-stop.log"

ts=$(date -u +%FT%TZ)
msg_len=${#last_msg}
msg_preview=$(printf '%s' "$last_msg" | head -c 200 | tr '\n' ' ')

printf '%s agent_type=%s agent_id=%s msg_len=%d preview=%q\n' \
    "$ts" "$agent_type" "$agent_id" "$msg_len" "$msg_preview" \
    >> "$log_file"

# Audit-class sanity check.
case "$agent_type" in
    unsafe-code-auditor|jni-bridge-verifier|async-cancel-safety|arch-layer-auditor|rust-api-auditor|kotlin-design-auditor)
        if [[ -z "$last_msg" ]] || ! grep -qiE 'findings?|audit|violations?|risk|cancel-safe|unsafe|reviewed' <<< "$last_msg"; then
            printf '%s WARNING %s returned message without audit-conclusion marker (msg_len=%d)\n' \
                "$ts" "$agent_type" "$msg_len" \
                >> "$log_file"
        fi
        ;;
esac

exit 0
