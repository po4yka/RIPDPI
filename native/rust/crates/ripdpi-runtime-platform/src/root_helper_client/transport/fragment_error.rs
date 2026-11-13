use std::io;

use ripdpi_privileged_ops::IpFragmentSendError;
use ripdpi_root_helper_protocol::{HelperResponse, IpFragmentErrorData};

pub(super) fn decode(response: HelperResponse) -> io::Error {
    let message = response.error.unwrap_or_else(|| "unknown helper error".to_owned());
    if response.data.is_null() {
        return io::Error::other(message);
    }
    let Ok(data) = serde_json::from_value::<IpFragmentErrorData>(response.data) else {
        return io::Error::new(io::ErrorKind::InvalidData, "invalid IP-fragment helper error metadata");
    };
    match data.into_source_error(message) {
        Ok((source, payload_commit_uncertain)) => {
            io::Error::new(source.kind(), IpFragmentSendError { source, payload_commit_uncertain })
        }
        Err(error) => error,
    }
}

#[cfg(test)]
mod tests;
