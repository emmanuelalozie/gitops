//! One error type for every command, serialized the same way for the frontend:
//! `{ kind, code, message, details }`. Engine errors keep the engine's own code and details
//! (see docs/engine-protocol.md), so the UI can, for example, mark the field a validation problem names.

use serde::Serialize;
use serde_json::Value;

#[derive(Debug, Clone, Serialize, thiserror::Error)]
#[error("{message}")]
pub struct AppError {
    /// Where it went wrong: engine, git, github, keychain, repo, io, internal.
    pub kind: &'static str,
    /// A stable code within the kind, such as the engine's `plan_changed`.
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Value::is_null")]
    pub details: Value,
}

impl AppError {
    pub fn new(kind: &'static str, code: impl Into<String>, message: impl Into<String>) -> Self {
        Self { kind, code: code.into(), message: message.into(), details: Value::Null }
    }

    pub fn with_details(mut self, details: Value) -> Self {
        self.details = details;
        self
    }

    pub fn repo(message: impl Into<String>) -> Self {
        Self::new("repo", "invalid_repo", message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new("internal", "internal", message)
    }
}

impl From<std::io::Error> for AppError {
    fn from(e: std::io::Error) -> Self {
        Self::new("io", "io", e.to_string())
    }
}

impl From<serde_json::Error> for AppError {
    fn from(e: serde_json::Error) -> Self {
        Self::new("internal", "json", e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, AppError>;
