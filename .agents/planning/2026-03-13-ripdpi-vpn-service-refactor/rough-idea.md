# Rough Idea

Create a PDD/TDD refactor plan for the single target file:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`

Goal:

- Safely narrow this oversized Android service to true service responsibilities while extracting runtime collaborators.

Required planning focus:

- Inspect and verify the responsibilities currently held by the service.
- Build a test-first incremental plan.
- Preserve service lifecycle semantics and runtime behavior.

Target architecture:

- Keep the Android `Service` focused on lifecycle and platform glue.
- Extract proxy and tunnel lifecycle coordination.
- Extract handover recovery and monitoring.
- Extract resolver override handling.
- Extract telemetry update logic.
- Extract policy application.
- Keep concurrency and lifecycle-sensitive code safe and explicit.

Testing strategy to plan:

- Characterization tests for service-level observable behavior where possible.
- Unit tests for extracted coordinators and policy logic.
- Integration or instrumentation-style tests only where Android and `VpnService` behavior requires it.
- Contract tests for lifecycle-driven interactions with extracted collaborators.

Deliverables:

- `requirements.md`
- `design.md`
- `implementation-plan.md`

Constraint:

- Do not implement the refactor in this planning session.
- Do not overwrite documentation from other active planning loops.
