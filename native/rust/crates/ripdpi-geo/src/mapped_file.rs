use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

use memmap2::{Mmap, MmapMut};
use thiserror::Error;

pub(crate) fn read_snapshot(path: &Path) -> Result<Mmap, MappedFileError> {
    let mut file = File::open(path)?;
    let len = usize::try_from(file.metadata()?.len())
        .ok()
        .filter(|&len| len <= isize::MAX as usize)
        .ok_or(MappedFileError::FileTooLarge)?;
    if len == 0 {
        return Err(MappedFileError::EmptyFile);
    }
    // Public callers can overwrite or truncate the source. An anonymous map
    // keeps reader memory valid without an unsafe file-immutability contract.
    let mut mapping = MmapMut::map_anon(len).map_err(MappedFileError::Map)?;
    file.read_exact(&mut mapping)?;
    mapping.make_read_only().map_err(MappedFileError::Map)
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
