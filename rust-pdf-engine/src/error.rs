use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("{0}")]
    InvalidInput(String),
    #[error("document not found")]
    NotFound,
    #[error("document is larger than the configured limit")]
    TooLarge,
    #[error("unsupported or malformed document: {0}")]
    Malformed(String),
    #[error("internal processing error")]
    Internal,
}

#[derive(Serialize)]
struct ErrorEnvelope {
    error: ErrorBody,
}

#[derive(Serialize)]
struct ErrorBody {
    code: &'static str,
    message: String,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, code) = match &self {
            Self::InvalidInput(_) => (StatusCode::BAD_REQUEST, "invalid_input"),
            Self::NotFound => (StatusCode::NOT_FOUND, "not_found"),
            Self::TooLarge => (StatusCode::PAYLOAD_TOO_LARGE, "document_too_large"),
            Self::Malformed(_) => (StatusCode::UNPROCESSABLE_ENTITY, "malformed_document"),
            Self::Internal => (StatusCode::INTERNAL_SERVER_ERROR, "internal_error"),
        };
        let message = self.to_string();
        (status, Json(ErrorEnvelope { error: ErrorBody { code, message } })).into_response()
    }
}
