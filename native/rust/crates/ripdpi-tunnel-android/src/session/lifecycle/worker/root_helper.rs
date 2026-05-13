#[cfg(any(target_os = "linux", target_os = "android"))]
pub(super) fn register_for_worker(config: &ripdpi_tunnel_config::Config) -> bool {
    let Some(path) = config.misc.root_helper_socket_path.as_deref() else {
        ripdpi_runtime_platform::root_helper::unregister_root_helper();
        return false;
    };
    if path.trim().is_empty() {
        ripdpi_runtime_platform::root_helper::unregister_root_helper();
        return false;
    }
    ripdpi_runtime_platform::root_helper::register_root_helper(path.to_owned());
    true
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(super) fn register_for_worker(_config: &ripdpi_tunnel_config::Config) -> bool {
    false
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(super) fn unregister_for_worker() {
    ripdpi_runtime_platform::root_helper::unregister_root_helper();
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(super) fn unregister_for_worker() {}
