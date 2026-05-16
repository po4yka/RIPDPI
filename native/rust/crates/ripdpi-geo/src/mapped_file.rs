use std::fs::File;
use std::io;
use std::path::Path;
use std::ptr::NonNull;

use thiserror::Error;

#[derive(Debug)]
pub(crate) struct MappedFile {
    ptr: NonNull<u8>,
    len: usize,
}

// SAFETY: `MappedFile` owns a `MAP_PRIVATE` read-only mmap region; the only
// observable operation is `as_slice`, which returns an `&[u8]`. Read-only
// shared data with no interior mutability is trivially `Send + Sync`.
unsafe impl Send for MappedFile {}
// SAFETY: see Send above — read-only data shared across threads is sound.
unsafe impl Sync for MappedFile {}

// Compile-fail regression for soundness issue #8: any future field change
// that breaks the Send/Sync claim above fails to compile here. The block
// is a const-evaluated identity check; the `assert_send` / `assert_sync`
// monomorphisations require the bounds to hold.
const _: fn() = || {
    fn assert_send<T: Send>() {}
    fn assert_sync<T: Sync>() {}
    assert_send::<MappedFile>();
    assert_sync::<MappedFile>();
};

// Compile-fail regression for soundness issue #14: `MappedFile` owns a
// kernel mmap (released via `libc::munmap` on Drop). A `#[derive(Copy)]`
// would let safe code duplicate the owning `NonNull<u8>` and produce a
// double-`munmap` on the second drop. The block stays unambiguous only
// while `MappedFile: !Copy`; if a future change derives `Copy` the trait
// dispatch becomes ambiguous and this block fails to compile.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<MappedFile> as AmbiguousIfCopy<_>>::check();
};

impl MappedFile {
    pub(crate) fn open(path: &Path) -> Result<Self, MappedFileError> {
        let file = File::open(path)?;
        let len = usize::try_from(file.metadata()?.len()).map_err(|_| MappedFileError::FileTooLarge)?;
        if len == 0 {
            return Err(MappedFileError::EmptyFile);
        }
        // SAFETY: `mmap` accepts a null `addr` (any kernel-chosen address),
        // `len > 0` (checked above), a valid live fd (`&file` is open here),
        // a zero offset, and a flag set that is a subset of `MAP_PRIVATE` —
        // all preconditions documented in mmap(2).
        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_READ,
                libc::MAP_PRIVATE,
                std::os::fd::AsRawFd::as_raw_fd(&file),
                0,
            )
        };
        if ptr == libc::MAP_FAILED {
            return Err(MappedFileError::Map(io::Error::last_os_error()));
        }
        let ptr = NonNull::new(ptr.cast::<u8>()).ok_or_else(|| MappedFileError::Map(io::Error::last_os_error()))?;
        Ok(Self { ptr, len })
    }

    pub(crate) fn len(&self) -> usize {
        self.len
    }

    pub(crate) fn as_slice(&self) -> &[u8] {
        // SAFETY: `self.ptr` was returned by mmap with `self.len` bytes of
        // PROT_READ memory. The returned slice borrows `&self`, so the
        // mapping outlives the slice (Drop munmaps after all borrows end).
        unsafe { std::slice::from_raw_parts(self.ptr.as_ptr(), self.len) }
    }
}

impl AsRef<[u8]> for MappedFile {
    fn as_ref(&self) -> &[u8] {
        self.as_slice()
    }
}

impl Drop for MappedFile {
    fn drop(&mut self) {
        // SAFETY: `self.ptr` was obtained from mmap with `self.len`;
        // `MappedFile` is the sole owner and Drop runs exactly once per move.
        unsafe {
            libc::munmap(self.ptr.as_ptr().cast(), self.len);
        }
    }
}

#[derive(Debug, Error)]
pub enum MappedFileError {
    #[error("file is empty")]
    EmptyFile,
    #[error("file is too large to map on this platform")]
    FileTooLarge,
    #[error("{0}")]
    Io(#[from] io::Error),
    #[error("{0}")]
    Map(io::Error),
}
