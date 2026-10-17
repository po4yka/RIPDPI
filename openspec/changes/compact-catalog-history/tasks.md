# UIX-1792213600805378

## Objective

Prioritize profile rows and make each history empty state offer the correct next action.

## Ownership

The portfolio issue defines the exact isolated writer paths. Root owns locales,
shared test tags, RipDpiNavHost, task artifacts, all builds and Git integration.
Workers own catalog and history presentation/tests in separate worktrees.

## Execution

- [x] UIX-1792213814306363 Define compact catalog and history state contracts #feature !high @item:UIX-1792213600805378
- [x] UIX-1792213814900821 Implement compact profile controls with behavior regressions #feature !high @item:UIX-1792213600805378
- [x] UIX-1792213815435833 Implement useful history states and navigation regressions #feature !high @item:UIX-1792213600805378
- [x] UIX-1792213816018346 Integrate locale resources and run combined local checks #feature !high @item:UIX-1792213600805378
- [x] UIX-1792213816520760 Inspect real Full and Simple APKs on the leased emulator #feature !high @item:UIX-1792213600805378
- [x] UIX-1792213817008369 Review and integrate verified changes into main and push #feature !high @item:UIX-1792213600805378

## Verification

Targeted Full/Simple unit and Compose tests, theme tests, app/service lint,
staticAnalysis, affected Roborazzi comparisons, architecture health, locked Cargo
metadata, actual Full/Simple APKs and command-leased emulator acceptance.
