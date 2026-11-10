# paste compatibility adapter

This repository-owned crate replaces crates.io `paste` through the workspace
`[patch.crates-io]` table. It contains no code from the archived `paste` crate.
It re-exports `paste`, `item`, and `expr` from `pastey` 0.2.3. The version
1.0.15 preserves the version requirements of existing Arti and netlink crates.
It is not a new upstream release or a copy of the old implementation.

`pastey` is a compile-time dependency. The adapter uses `no_std` so its target
library does not add a standard-library requirement to downstream crates.
The adapter is a workspace member so the CI workspace tests cover its API.

## Dependency review

The published `pastey` 0.2.3 archive is
<https://static.crates.io/crates/pastey/pastey-0.2.3.crate>.
Its SHA-256 is
`2ee67f1008b1ba2321834326597b8e186293b049a023cdef258527550b9935b4`.
The package was published on 2026-05-20 under MIT OR Apache-2.0. It has no
dependencies or build script. The 2026-11-10 source review found no unsafe
code, network calls, process execution, or file access. Environment reads
resolve explicit `env!` fragments in macro input, as in the old API.

Remove this adapter when the upstream dependency graph no longer needs `paste`.
