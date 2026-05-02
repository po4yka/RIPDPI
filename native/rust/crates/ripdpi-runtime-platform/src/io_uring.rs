/// Return io_uring capabilities detected at startup.
pub fn io_uring_capabilities() -> ripdpi_io_uring::IoUringCapabilities {
    ripdpi_io_uring::io_uring_capabilities()
}
