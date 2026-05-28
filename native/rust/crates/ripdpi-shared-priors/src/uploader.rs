//! Opt-in shared-priors contribution gate.
//!
//! This crate owns the coarse payload schema and opt-in guard only. It does not
//! perform HTTP upload; Android-owned fetch/apply of signed priors bundles uses
//! the manifest/parser APIs instead.

use crate::coarse_payload::CoarsePayload;

/// Errors returned by [`OptInUploader::submit`].
#[derive(Debug)]
pub enum UploadError {
    /// The user has not opted in; the payload was dropped without being sent.
    NotOptedIn,
}

impl std::fmt::Display for UploadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotOptedIn => write!(f, "shared-priors upload skipped: user has not opted in"),
        }
    }
}

impl std::error::Error for UploadError {}

/// Contribution gate that enforces opt-in before accepting a payload.
///
/// Default opt-in state is **false** — contributions are never sent unless
/// the user explicitly enables the feature.
pub struct OptInUploader {
    opted_in: bool,
}

impl OptInUploader {
    /// Create a new uploader. Pass `opted_in: true` only when the user has
    /// explicitly enabled shared-priors contributions in the UI.
    pub fn new(opted_in: bool) -> Self {
        Self { opted_in }
    }

    /// Accept a [`CoarsePayload`] for the shared-priors contribution path.
    ///
    /// Returns `Err(UploadError::NotOptedIn)` immediately if the opt-in flag
    /// is `false`. When opted in, this consumes the payload after the privacy
    /// boundary has been enforced; no network transport is implemented here.
    pub fn submit(&self, payload: CoarsePayload) -> Result<(), UploadError> {
        if !self.opted_in {
            return Err(UploadError::NotOptedIn);
        }
        // Payload is consumed here to prevent accidental re-use after the
        // opt-in boundary has accepted it.
        let _ = payload;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::coarse_payload::{AccessType, CoarseKey, CoarsePayload, DnsClass, FailPhase};

    fn sample_payload() -> CoarsePayload {
        CoarsePayload {
            keys: vec![CoarseKey {
                asn: 1,
                access_type: AccessType::Wifi,
                dns_class: DnsClass::Doh,
                udp443_ok: true,
                fail_phase: FailPhase::Tls,
            }],
            win_count: 1,
            loss_count: 0,
        }
    }

    /// Default opt-in is false: submit must fail with NotOptedIn.
    #[test]
    fn default_opt_in_is_false_submit_fails_with_not_opted_in() {
        let uploader = OptInUploader::new(false);
        let err = uploader.submit(sample_payload()).expect_err("must fail when not opted in");
        assert!(matches!(err, UploadError::NotOptedIn), "got {err:?}");
    }

    /// When opted in, submit succeeds and consumes the coarse payload.
    #[test]
    fn opted_in_submit_succeeds() {
        let uploader = OptInUploader::new(true);
        uploader.submit(sample_payload()).expect("opted-in submit must succeed");
    }
}
