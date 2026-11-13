use std::io;

pub(crate) fn capability_error(source: io::Error) -> io::Error {
    if matches!(source.raw_os_error(), Some(libc::EPERM | libc::EACCES)) {
        io::Error::new(io::ErrorKind::Unsupported, source)
    } else {
        source
    }
}

pub(crate) fn protection_error(source: io::Error) -> io::Error {
    io::Error::other(source)
}

pub(crate) fn validate_fragment_mtu(lengths: [usize; 2], mtu: u32) -> io::Result<()> {
    if mtu == 0 || lengths.into_iter().any(|length| length > mtu as usize) {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "IP fragment pair exceeds the path MTU"));
    }
    Ok(())
}

pub(crate) fn tcp_fragment_build_error(source: ripdpi_ipfrag::BuildError) -> io::Error {
    let kind = match source {
        ripdpi_ipfrag::BuildError::ValueTooLarge => io::ErrorKind::Unsupported,
        ripdpi_ipfrag::BuildError::InvalidSplit { .. } => io::ErrorKind::InvalidInput,
        ripdpi_ipfrag::BuildError::AddressFamilyMismatch => io::ErrorKind::InvalidData,
    };
    io::Error::new(kind, source)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fragment_capability_denials_allow_pre_send_fallback() {
        for errno in [libc::EPERM, libc::EACCES] {
            let error = capability_error(io::Error::from_raw_os_error(errno));
            assert_eq!(error.kind(), io::ErrorKind::Unsupported);
            let source = error.get_ref().unwrap().downcast_ref::<io::Error>().unwrap();
            assert_eq!(source.raw_os_error(), Some(errno));
        }
        assert_eq!(capability_error(io::Error::from_raw_os_error(libc::EBADF)).raw_os_error(), Some(libc::EBADF));
    }

    #[test]
    fn every_protection_failure_is_terminal_even_with_a_fallback_error_kind() {
        for kind in [
            io::ErrorKind::Unsupported,
            io::ErrorKind::InvalidInput,
            io::ErrorKind::WouldBlock,
            io::ErrorKind::PermissionDenied,
        ] {
            let error = protection_error(io::Error::from(kind));
            assert_eq!(error.kind(), io::ErrorKind::Other);
            assert_eq!(error.get_ref().unwrap().downcast_ref::<io::Error>().unwrap().kind(), kind);
        }
    }

    #[test]
    fn oversized_tcp_datagram_is_a_safe_size_fallback() {
        let spec = ripdpi_ipfrag::TcpFragmentSpec {
            src: "192.0.2.1:40000".parse().unwrap(),
            dst: "198.51.100.2:443".parse().unwrap(),
            ttl: 64,
            identification: 1,
            sequence_number: 1,
            acknowledgment_number: 2,
            window_size: 4096,
            timestamp: None,
            tcp_flags_set: 0,
            tcp_flags_unset: 0,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        };
        let error = ripdpi_ipfrag::build_tcp_fragment_pair(spec, &vec![0x41; 65496], 1)
            .expect_err("one byte beyond the IPv4 original length limit");
        assert_eq!(error, ripdpi_ipfrag::BuildError::ValueTooLarge);
        assert_eq!(tcp_fragment_build_error(error).kind(), io::ErrorKind::Unsupported);
        assert_eq!(
            tcp_fragment_build_error(ripdpi_ipfrag::BuildError::AddressFamilyMismatch).kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn both_fragments_must_fit_the_known_path_mtu() {
        assert!(validate_fragment_mtu([1500, 48], 1500).is_ok());
        assert!(validate_fragment_mtu([48, 1500], 1500).is_ok());
        for (lengths, mtu) in
            [([1501, 48], 1500), ([48, 1501], 1500), ([1281, 48], 1280), ([48, 1281], 1280), ([48, 48], 0)]
        {
            assert_eq!(validate_fragment_mtu(lengths, mtu).unwrap_err().kind(), io::ErrorKind::Unsupported);
        }
    }
}
