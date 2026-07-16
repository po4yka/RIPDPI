#[cfg(any(target_os = "linux", target_os = "android"))]
pub(super) type WorkerRootHelperToken = ripdpi_runtime_platform::root_helper::RootHelperGeneration;

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(super) struct WorkerRootHelperToken;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(super) fn register_for_worker(config: &ripdpi_tunnel_config::Config) -> Option<WorkerRootHelperToken> {
    let Some(path) = config.misc.root_helper_socket_path.as_deref() else {
        ripdpi_runtime_platform::root_helper::unregister_root_helper();
        return None;
    };
    if path.trim().is_empty() {
        ripdpi_runtime_platform::root_helper::unregister_root_helper();
        return None;
    }
    Some(ripdpi_runtime_platform::root_helper::register_root_helper_versioned(path.to_owned()))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(super) fn register_for_worker(_config: &ripdpi_tunnel_config::Config) -> Option<WorkerRootHelperToken> {
    None
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(super) fn unregister_for_worker(generation: WorkerRootHelperToken) {
    ripdpi_runtime_platform::root_helper::unregister_root_helper_if(generation);
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(super) fn unregister_for_worker(_generation: WorkerRootHelperToken) {}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use crate::config::{config_from_payload, sample_payload};

    use super::{register_for_worker, unregister_for_worker};

    #[test]
    fn stale_worker_release_does_not_clear_newer_root_helper_registration() {
        ripdpi_runtime_platform::root_helper::unregister_root_helper();
        let mut stale_config = config_from_payload(sample_payload()).expect("stale config");
        stale_config.misc.root_helper_socket_path = Some("/tmp/ripdpi-old-root-helper.sock".to_string());
        let stale = register_for_worker(&stale_config).expect("stale generation");

        let mut current_config = config_from_payload(sample_payload()).expect("current config");
        current_config.misc.root_helper_socket_path = Some("/tmp/ripdpi-current-root-helper.sock".to_string());
        let current = register_for_worker(&current_config).expect("current generation");

        unregister_for_worker(stale);
        assert_eq!(
            ripdpi_runtime_platform::root_helper::with_root_helper(
                ripdpi_runtime_platform::root_helper_client::RootHelperClient::socket_path,
            ),
            Some("/tmp/ripdpi-current-root-helper.sock".to_string()),
        );

        unregister_for_worker(current);
        assert!(!ripdpi_runtime_platform::root_helper::has_root_helper());
    }
}
