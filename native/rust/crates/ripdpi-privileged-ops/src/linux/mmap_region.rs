//! RAII anonymous-mmap region used by the raw fake-TCP injection path.
//!
//! The region is allocated with `mmap(MAP_PRIVATE | MAP_ANON)` and
//! unmapped on drop. Writes are bounds-checked. The pointer is not exposed
//! to sibling modules; `vmsplice` interop stays inside this RAII owner so
//! callers cannot retain a raw pointer past the region lifetime.

use std::io;
use std::num::NonZeroUsize;
use std::os::fd::{AsRawFd, BorrowedFd};
use std::ptr;
use std::ptr::NonNull;

use nix::sys::mman::{mmap_anonymous, munmap, MapFlags, ProtFlags};

/// An anonymous private mmap region of fixed length.
///
/// Drop unmaps the region. The region is `!Send` / `!Sync` by default
/// because callers in this crate use it from a single thread.
pub(super) struct MmapRegion {
    ptr: NonNull<u8>,
    len: NonZeroUsize,
}

impl MmapRegion {
    /// Map a new anonymous read/write region of `len` bytes.
    pub(super) fn new(len: usize) -> io::Result<Self> {
        let size = NonZeroUsize::new(len)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "zero-length mmap region"))?;
        // SAFETY: anonymous private mapping; no backing fd; no aliasing with
        // existing mappings.
        let ptr =
            unsafe { mmap_anonymous(None, size, ProtFlags::PROT_READ | ProtFlags::PROT_WRITE, MapFlags::MAP_PRIVATE) }
                .map_err(io::Error::from)?;
        Ok(Self { ptr: ptr.cast(), len: size })
    }

    /// Zero the region, then copy `data` (truncated to fit) into the head.
    /// Safe because the region's bounds are owned and known.
    pub(super) fn write(&mut self, data: &[u8]) {
        let len = self.len.get();
        // SAFETY: `self.ptr` points to a writable mapping of exactly `len`
        // bytes (invariant established at construction).
        unsafe { ptr::write_bytes(self.ptr.as_ptr(), 0, len) };
        // SAFETY: the copy length is truncated to the mapping length, and
        // `data` and the region are disjoint allocations.
        unsafe { ptr::copy_nonoverlapping(data.as_ptr(), self.ptr.as_ptr(), data.len().min(len)) };
    }

    /// Queue the first `len` bytes into `fd` with `vmsplice(2)`.
    ///
    /// The raw mmap pointer and `iovec` never leave this RAII owner, keeping
    /// the unsafe syscall handoff local to the mapping lifetime.
    pub(super) fn vmsplice_to(&mut self, fd: BorrowedFd<'_>, len: usize) -> io::Result<usize> {
        if len > self.len.get() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "vmsplice length exceeds mmap region length"));
        }
        let iov = libc::iovec { iov_base: self.ptr.as_ptr().cast(), iov_len: len };
        // SAFETY: `iov` points into a writable mapping of at least `len`
        // bytes owned by `self`; the mapping is live for the duration of
        // this call, and `fd` is a live pipe descriptor borrowed by the
        // caller.
        let queued = unsafe { libc::vmsplice(fd.as_raw_fd(), &iov, 1, 0) };
        if queued >= 0 {
            Ok(queued as usize)
        } else {
            Err(io::Error::last_os_error())
        }
    }

    #[cfg(test)]
    pub(super) fn to_vec(&self) -> Vec<u8> {
        let len = self.len.get();
        let mut out = vec![0_u8; len];
        // SAFETY: `self.ptr` points to a readable mapping of exactly `len`
        // bytes and `out` is a distinct writable allocation of the same
        // length.
        unsafe { ptr::copy_nonoverlapping(self.ptr.as_ptr(), out.as_mut_ptr(), len) };
        out
    }
}

impl Drop for MmapRegion {
    fn drop(&mut self) {
        // SAFETY: `self.ptr` was returned by `mmap_anonymous` with `self.len`
        // bytes and has not been unmapped (Drop runs exactly once per move
        // of `Self`). `MmapRegion` is the sole owner; no aliasing handle
        // exists because the raw pointer is never returned by-value to
        // safe callers.
        let _ = unsafe { munmap(self.ptr.cast(), self.len.get()) };
    }
}

// Compile-fail regression for soundness issue #8: `MmapRegion` is documented
// as `!Send` / `!Sync` because callers use it from a single thread and the
// raw pointer field is `NonNull<u8>` which auto-derives `!Send`. The block
// below uses trait-dispatch ambiguity (stable-Rust equivalent of
// `static_assertions::assert_not_impl_any!`) so that any future change
// adding `unsafe impl Send for MmapRegion {}` causes the workspace build
// to fail: the two `AmbiguousIfSend` impls would both apply, leaving the
// `_` in `AmbiguousIfSend<_>` ambiguous.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T: ?Sized>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfSend<A> {
        fn check() {}
    }
    impl<T: ?Sized> AmbiguousIfSend<()> for Check<T> {}
    impl<T: ?Sized + Send> AmbiguousIfSend<u8> for Check<T> {}
    <Check<MmapRegion> as AmbiguousIfSend<_>>::check();
};

const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T: ?Sized>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfSync<A> {
        fn check() {}
    }
    impl<T: ?Sized> AmbiguousIfSync<()> for Check<T> {}
    impl<T: ?Sized + Sync> AmbiguousIfSync<u8> for Check<T> {}
    <Check<MmapRegion> as AmbiguousIfSync<_>>::check();
};

// Compile-fail regression for soundness issue #14: `MmapRegion` owns a
// kernel mmap and unmaps it on `Drop`. A `#[derive(Copy)]` would let
// safe code silently duplicate the owning `NonNull<u8>` and produce a
// double-`munmap` on the second drop. The two `AmbiguousIfCopy` impls
// stay unambiguous only while `MmapRegion: !Copy`; if a future change
// derives `Copy` the trait dispatch becomes ambiguous and this block
// fails to compile.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<MmapRegion> as AmbiguousIfCopy<_>>::check();
};
