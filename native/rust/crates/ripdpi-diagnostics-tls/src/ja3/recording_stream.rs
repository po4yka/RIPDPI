use std::io::{self, Read, Write};

/// Wraps a `Read + Write` stream and records all bytes successfully written.
pub struct RecordingStream<S> {
    inner: S,
    recorded_writes: Vec<u8>,
}

impl<S> RecordingStream<S> {
    pub fn new(stream: S) -> Self {
        Self { inner: stream, recorded_writes: Vec::with_capacity(512) }
    }

    pub fn recorded_writes(&self) -> &[u8] {
        &self.recorded_writes
    }

    pub fn inner_mut(&mut self) -> &mut S {
        &mut self.inner
    }

    pub fn into_parts(self) -> (S, Vec<u8>) {
        (self.inner, self.recorded_writes)
    }
}

impl<S: Read> Read for RecordingStream<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.inner.read(buf)
    }
}

impl<S: Write> Write for RecordingStream<S> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.inner.write(buf)?;
        self.recorded_writes.extend_from_slice(&buf[..n]);
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}
