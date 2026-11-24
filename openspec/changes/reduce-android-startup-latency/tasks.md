# AND-1795494902099648: Reduce Android startup latency

## Objective

Open the first usable Android screen sooner and detect slower startup.

## Ownership

One worktree owns app startup, diagnostics initialization and data access, the startup benchmark, and its CI lane.

## Execution

## Verification

Run the startup, diagnostics, and regression detector tests. Confirm TTID and TTFD on the physical Pixel and on the managed CI lane.
- [x] AND-1795495009491088 Reduce and trace diagnostics startup work #feature !high @item:AND-1795494902099648
- [x] AND-1795495010036353 Defer startup layout and report the first usable draw #feature !high @item:AND-1795494902099648
- [ ] AND-1795495010525115 Run release-derived startup benchmarks and enforce a measured reference #feature !high @item:AND-1795494902099648
