/// A terminal pool closure or an error returned by the protocol backend.
#[derive(Debug, thiserror::Error)]
pub enum RelayOpenError<E> {
    #[error("relay pool is shut down")]
    Closed,
    #[error(transparent)]
    Backend(#[from] E),
}
