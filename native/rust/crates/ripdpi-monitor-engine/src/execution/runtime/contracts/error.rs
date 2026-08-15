/// Failure modes when preparing or launching a candidate probe runtime.
///
/// `Display` is byte-for-byte compatible with the previous
/// `Result<_, String>` contract so that error text surfaced where it reaches a
/// `String` boundary (the `rationale` field of a failed candidate summary, JNI)
/// is unchanged.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum CandidateRuntimeError {
    /// No launcher was wired into the session (default `Unavailable` launcher).
    #[error("candidate runtime launcher is not configured")]
    LauncherUnavailable,
    /// Building the runtime config from the UI candidate spec failed.
    #[error("{0}")]
    Preparation(String),
    /// The launcher could not bring the candidate runtime up.
    #[error("{0}")]
    Launch(String),
}
