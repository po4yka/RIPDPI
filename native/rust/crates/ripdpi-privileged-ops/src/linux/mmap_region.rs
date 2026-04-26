use std::io;
use std::num::NonZeroUsize;
use std::ptr;

use nix::sys::mman::{mmap_anonymous, munmap, MapFlags, ProtFlags};

pub(super) fn alloc_region(len: usize) -> io::Result<*mut u8> {
    let size =
        NonZeroUsize::new(len).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "zero-length mmap region"))?;
    // SAFETY: anonymous private mapping; no backing fd; no aliasing with existing mappings.
    let ptr =
        unsafe { mmap_anonymous(None, size, ProtFlags::PROT_READ | ProtFlags::PROT_WRITE, MapFlags::MAP_PRIVATE) }
            .map_err(io::Error::from)?;
    Ok(ptr.as_ptr().cast())
}

pub(super) fn free_region(region: *mut u8, len: usize) {
    if let Some(ptr) = std::ptr::NonNull::new(region) {
        if len != 0 {
            // SAFETY: `region` was allocated by mmap with the same length.
            let _ = unsafe { munmap(ptr.cast(), len) };
        }
    }
}

pub(super) fn write_region(region: *mut u8, data: &[u8], len: usize) {
    // SAFETY: `region` points to a writable mapping of `len` bytes.
    unsafe {
        ptr::write_bytes(region, 0, len);
        ptr::copy_nonoverlapping(data.as_ptr(), region, data.len().min(len));
    }
}
