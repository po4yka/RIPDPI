# Summary

## Artifacts

This planning workspace contains:

1. `rough-idea.md`
2. `requirements.md`
3. `design.md`
4. `implementation-plan.md`

## Plan Overview

The plan treats `DiagnosticsViewModel.kt` as an oversized mixed-responsibility component and breaks the refactor into behavior-safe slices. It starts by strengthening characterization coverage, then extracts typed state inputs, pure presentation helpers, mappers, reducers, and only later moves workflow orchestration such as audit auto-open and archive/share handling.

## Next Steps

1. Review the requirements, design, and implementation plan in this directory.
2. Add the planning files to your tool context if you want to continue with implementation in a later loop:
   `/context add .agents/planning/2026-03-13-diagnostics-viewmodel-refactor/**/*.md`
3. When you want implementation, start the Ralph handoff yourself with one of:
   `ralph run --config presets/pdd-to-code-assist.yml --prompt "Implement the DiagnosticsViewModel refactor plan from .agents/planning/2026-03-13-diagnostics-viewmodel-refactor"`
   `ralph run -c ralph.yml -H builtin:pdd-to-code-assist -p "Implement the DiagnosticsViewModel refactor plan from .agents/planning/2026-03-13-diagnostics-viewmodel-refactor"`
