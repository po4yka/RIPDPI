use std::io;

use ripdpi_failure_classifier::FailureClass;

/// Typed payload for relay errors whose [`FailureClass`] is known at
/// construction. The class travels as data so downstream code and tests can
/// match on it directly; the `Display` text keeps the historical
/// `"{token}: {message}"` shape that the telemetry surface records.
#[derive(Debug)]
pub(crate) struct ClassifiedRelayError {
    class: FailureClass,
    message: String,
}

impl std::fmt::Display for ClassifiedRelayError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.class.as_str(), self.message)
    }
}

impl std::error::Error for ClassifiedRelayError {}

/// Build an io error tagged with `class`. Its display text is
/// `"{class.as_str()}: {message}"`, byte-identical to the historical
/// token-prefix encoding.
pub(crate) fn classified_error(class: FailureClass, kind: io::ErrorKind, message: impl Into<String>) -> io::Error {
    io::Error::new(kind, ClassifiedRelayError { class, message: message.into() })
}

/// Recover the failure class tagged by [`classified_error`], if any.
///
/// Today's consumers are the contract tests pinning end-to-end class
/// propagation; production classification wiring (the failure-classifier
/// boundary) consumes this same accessor once relay failures feed it.
#[cfg_attr(not(test), allow(dead_code))]
pub(crate) fn relay_failure_class(error: &io::Error) -> Option<FailureClass> {
    error.get_ref().and_then(|payload| payload.downcast_ref::<ClassifiedRelayError>()).map(|payload| payload.class)
}
