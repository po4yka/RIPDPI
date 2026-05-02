use super::dto::HttpsSvcbParseError;

pub(super) struct ByteCursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> ByteCursor<'a> {
    pub(super) fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    pub(super) fn is_empty(&self) -> bool {
        self.offset == self.bytes.len()
    }

    pub(super) fn read_u8(&mut self, label: &str) -> Result<u8, HttpsSvcbParseError> {
        let bytes = self.read_bytes(1, label)?;
        Ok(bytes[0])
    }

    pub(super) fn read_u16(&mut self, label: &str) -> Result<u16, HttpsSvcbParseError> {
        let bytes = self.read_bytes(2, label)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    pub(super) fn read_vec_u8(&mut self, label: &str) -> Result<&'a [u8], HttpsSvcbParseError> {
        let len = usize::from(self.read_u8(label)?);
        self.read_bytes(len, label)
    }

    pub(super) fn read_vec_u16(&mut self, label: &str) -> Result<&'a [u8], HttpsSvcbParseError> {
        let len = usize::from(self.read_u16(label)?);
        self.read_bytes(len, label)
    }

    pub(super) fn read_bytes(&mut self, len: usize, label: &str) -> Result<&'a [u8], HttpsSvcbParseError> {
        let end = self.offset.saturating_add(len);
        if end > self.bytes.len() {
            return Err(HttpsSvcbParseError::MalformedEchConfigList(format!(
                "{label} truncated at byte {}",
                self.offset
            )));
        }
        let slice = &self.bytes[self.offset..end];
        self.offset = end;
        Ok(slice)
    }

    pub(super) fn expect_empty(&self, label: &str) -> Result<(), HttpsSvcbParseError> {
        if self.is_empty() {
            return Ok(());
        }
        Err(HttpsSvcbParseError::MalformedEchConfigList(format!(
            "{label}: {} trailing bytes",
            self.bytes.len() - self.offset
        )))
    }
}
