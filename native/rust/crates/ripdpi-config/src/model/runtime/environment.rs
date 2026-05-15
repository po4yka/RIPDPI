/// Coarse classification of the device hosting the RIPDPI runtime.
///
/// Used by the offline learner to distinguish bandit
/// statistics gathered on real user devices (`Field`) from those gathered
/// on Android emulators or CI test devices (`Emulator`). Including this in
/// the strategy evolver's `LearningContext` (the bandit's HashMap key)
/// segregates the two populations automatically — emulator runs cannot
/// pollute field priors, and field runs are not biased by simulator-only
/// network characteristics. `Unknown` is the conservative default for
/// builds that have not wired the platform-side detector.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum EnvironmentKind {
    #[default]
    Unknown,
    Field,
    Emulator,
}
