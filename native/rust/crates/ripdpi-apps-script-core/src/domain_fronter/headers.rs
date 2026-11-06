pub(super) fn value<'a>(headers: &'a [(String, String)], name: &str) -> Option<&'a str> {
    headers.iter().find(|(key, _)| key.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
}

pub(crate) fn validate_transfer_encoding(headers: &::http::HeaderMap) -> Result<(), super::FronterError> {
    let mut encodings = headers.get_all(::http::header::TRANSFER_ENCODING).iter();
    if let Some(value) = encodings.next()
        && (encodings.next().is_some() || !value.as_bytes().eq_ignore_ascii_case(b"chunked"))
    {
        return Err(super::FronterError::BadResponse("unsupported transfer encoding".into()));
    }
    Ok(())
}
