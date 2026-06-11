use std::io;

/// Convert any [`Display`](std::fmt::Display) error into an [`io::Error`] with
/// [`io::ErrorKind::Other`].
///
/// Used throughout this crate to adapt protocol-specific errors into the
/// `io::Result` surface exposed by `RelaySession` / `RelaySessionFactory`.
/// The `to_string()` call is intentional: it gives the caller a clean,
/// human-readable message and avoids surfacing internal type-erased error
/// chains that differ between protocol libraries.
///
/// # Why not use the `Error + Send + Sync + 'static` overload?
///
/// Some call sites (e.g. `tor.rs`) use `io::Error::other(error)` directly so
/// the full error chain is preserved. Those sites keep their own local helper
/// with the broader bound. This function is for the common case where the
/// protocol library returns a display-only or non-`'static` error type.
pub(crate) fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
