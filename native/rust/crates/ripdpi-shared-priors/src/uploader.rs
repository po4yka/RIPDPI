//! Opt-in shared-priors uploader.
//!
//! The uploader is a no-op unless the user has explicitly opted in.
//! The real network upload path is a placeholder (`Ok(())`) — actual HTTP
//! transport is deferred to a follow-up task to keep this change focused on
//! the privacy-boundary scaffolding.

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

/// Uploader that enforces the opt-in gate before touching the network.
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

    /// Submit a [`CoarsePayload`] to the shared-priors endpoint.
    ///
    /// Returns `Err(UploadError::NotOptedIn)` immediately if the opt-in flag
    /// is `false`. The real network path is a placeholder that returns `Ok(())`
    /// — live upload is deferred to a follow-up task.
    pub fn submit(&self, payload: CoarsePayload) -> Result<(), UploadError> {
        if !self.opted_in {
            return Err(UploadError::NotOptedIn);
        }
        // Placeholder: real HTTP upload is deferred.
        // SAFETY: payload is consumed here to prevent accidental re-use.
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

    /// When opted in, submit succeeds (placeholder path returns Ok).
    #[test]
    fn opted_in_submit_succeeds() {
        let uploader = OptInUploader::new(true);
        uploader.submit(sample_payload()).expect("opted-in submit must succeed");
    }
}
