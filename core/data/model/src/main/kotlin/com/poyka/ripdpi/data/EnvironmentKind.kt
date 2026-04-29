package com.poyka.ripdpi.data

// Coarse classification of the device hosting the RIPDPI runtime,
// mirroring `ripdpi_config::EnvironmentKind` on the Rust side. Used by
// the offline learner (P4.4.5, ADR-011) to distinguish bandit
// statistics gathered on real user devices (`Field`) from those
// gathered on Android emulators or CI test devices (`Emulator`).
//
// Variant names are the JSON wire form -- the Kotlin → Rust config
// bridge serializes the enum's `name` directly, and the Rust side
// matches against the same strings.
enum class EnvironmentKind {
    Unknown,
    Field,
    Emulator,
}
