mod cdn_ech;
mod shared_priors;

pub use cdn_ech::{refresh_entry, seed_entry, snapshot_entry};
pub use shared_priors::apply_entry;

pub fn seqovl_supported() -> bool {
    ripdpi_runtime_platform::tcp::seqovl_supported()
}
