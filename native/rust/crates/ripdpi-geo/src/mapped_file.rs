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

unsafe impl Send for MappedFile {}
unsafe impl Sync for MappedFile {}

impl MappedFile {
    pub(crate) fn open(path: &Path) -> Result<Self, MappedFileError> {
        let file = File::open(path)?;
        let len = usize::try_from(file.metadata()?.len()).map_err(|_| MappedFileError::FileTooLarge)?;
        if len == 0 {
            return Err(MappedFileError::EmptyFile);
        }
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
        unsafe { std::slice::from_raw_parts(self.ptr.as_ptr(), self.len) }
    }
}

impl Drop for MappedFile {
    fn drop(&mut self) {
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
