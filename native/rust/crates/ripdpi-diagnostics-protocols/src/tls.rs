pub use ripdpi_diagnostics_tls::tls::{
    classify_tls_signal, is_server_tls_version_rejection, open_probe_stream_targets, preferred_tls_observation,
    tls_key_log_callback_for_path, try_tls_handshake, try_tls_handshake_with_key_log, TlsClientProfile,
    TlsKeyLogCallback, TlsObservation,
};
