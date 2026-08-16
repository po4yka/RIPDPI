use std::io;

use crate::tcp_fake_family::TcpStepControl;
use crate::types::OutboundSendError;

pub(super) fn execute_multi_disorder_step() -> Result<(usize, TcpStepControl), OutboundSendError> {
    invalid_data("multidisorder must be executed as a grouped tcp plan")
}

pub(super) fn execute_tls_prelude_step() -> Result<(usize, TcpStepControl), OutboundSendError> {
    invalid_data("tls prelude step must not appear in tcp send plan")
}

pub(super) fn execute_unknown_step() -> Result<(usize, TcpStepControl), OutboundSendError> {
    invalid_data("unknown tcp step kind in tcp send plan")
}

fn invalid_data(message: &'static str) -> Result<(usize, TcpStepControl), OutboundSendError> {
    Err(OutboundSendError::transport(io::Error::new(io::ErrorKind::InvalidData, message)))
}
