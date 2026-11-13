use std::io;

#[derive(Debug)]
struct TcpRepairRestoreError {
    operation: Option<io::Error>,
    restoration: io::Error,
}

impl std::fmt::Display for TcpRepairRestoreError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "TCP_REPAIR restoration failed: {}", self.restoration)?;
        if let Some(operation) = &self.operation {
            write!(formatter, "; TCP send failed: {operation}")?;
        }
        Ok(())
    }
}

impl std::error::Error for TcpRepairRestoreError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.operation.as_ref().unwrap_or(&self.restoration))
    }
}

pub(crate) fn finish_tcp_repair(operation: io::Result<()>, repair_restoration: io::Result<()>) -> io::Result<()> {
    match repair_restoration {
        Ok(()) => operation,
        Err(restoration) => Err(io::Error::other(TcpRepairRestoreError { operation: operation.err(), restoration })),
    }
}

#[cfg(test)]
mod tests {
    use super::finish_tcp_repair;

    #[test]
    fn tcp_send_restoration_failure_is_terminal_and_keeps_original_error() {
        let error = finish_tcp_repair(
            Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "raw preflight unavailable")),
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "repair off denied")),
        )
        .expect_err("uncertain repair state must prevent fallback");
        assert_eq!(error.kind(), std::io::ErrorKind::Other);
        assert!(error.to_string().contains("repair off denied"));
        assert!(error.to_string().contains("raw preflight unavailable"));
        assert_eq!(
            std::error::Error::source(&error).expect("original operation error").to_string(),
            "raw preflight unavailable"
        );
    }

    #[test]
    fn tcp_send_restoration_success_preserves_preflight_kind() {
        let error = finish_tcp_repair(Err(std::io::Error::from(std::io::ErrorKind::WouldBlock)), Ok(()))
            .expect_err("preflight refused");
        assert_eq!(error.kind(), std::io::ErrorKind::WouldBlock);
        assert!(finish_tcp_repair(Ok(()), Ok(())).is_ok());
    }

    #[test]
    fn successful_tcp_send_still_reports_restoration_failure() {
        let error = finish_tcp_repair(
            Ok(()),
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "repair off denied")),
        )
        .expect_err("sent payload cannot hide failed repair restoration");
        assert_eq!(error.kind(), std::io::ErrorKind::Other);
        assert!(error.to_string().contains("repair off denied"));
        assert_eq!(std::error::Error::source(&error).expect("restoration error").to_string(), "repair off denied");
    }
}
