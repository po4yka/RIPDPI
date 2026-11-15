use std::io;

use serde::Deserialize;

use crate::{HttpRequestId, cancel_http_request, create_http_request, execute_http_request, release_http_request};

#[derive(Deserialize)]
#[serde(tag = "operation", rename_all = "camelCase")]
enum HttpCommand {
    Create,
    Execute {
        #[serde(rename = "requestHandle")]
        handle: i64,
        #[serde(rename = "requestJson")]
        request: String,
    },
    Cancel {
        #[serde(rename = "requestHandle")]
        handle: i64,
    },
    Release {
        #[serde(rename = "requestHandle")]
        handle: i64,
    },
}

pub(crate) fn execute(request_json: &str) -> io::Result<String> {
    let value: serde_json::Value =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    if value.get("operation").is_none() {
        return crate::executor::execute(request_json);
    }
    let command = serde_json::from_value(value).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    match command {
        HttpCommand::Create => Ok(serde_json::json!({"requestHandle": create_http_request().as_jni()}).to_string()),
        HttpCommand::Execute { handle, request } => execute_http_request(HttpRequestId::from_jni(handle), &request),
        HttpCommand::Cancel { handle } => {
            cancel_http_request(HttpRequestId::from_jni(handle));
            Ok("{}".to_owned())
        }
        HttpCommand::Release { handle } => {
            release_http_request(HttpRequestId::from_jni(handle));
            Ok("{}".to_owned())
        }
    }
}
