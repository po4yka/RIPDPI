## Task contract

- Task ID: <!-- AREA-16-digit-ID -->
- Task record: <!-- docs/tasks/issues/<slug>.md -->
- OpenSpec change: <!-- change name, or N/A -->
- Spec-not-required reason: <!-- allowed reason, or N/A -->

## Summary

<!-- What observable outcome does this PR deliver and why? -->

## Evidence

<!-- Exact commands, hosted run, device/emulator, artifact, and deployment evidence as applicable. -->

## Checklist

- [ ] `just task-check` passes
- [ ] No work is marked complete without its required evidence
- [ ] No baseline files extended (detekt, lint, LoC)
- [ ] `timeout-minutes` added to any new CI job
- [ ] Native ABI matrix not broken (if touching native builds)
- [ ] Non-rooted device path still works (if touching VPN/root features)
- [ ] mdtask PolyForm Shield internal-tool use is owner/legal-approved (if `tools/tasking` changes)
