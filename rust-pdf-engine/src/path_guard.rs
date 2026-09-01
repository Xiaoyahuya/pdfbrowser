use std::path::{Component, Path, PathBuf};

use crate::{error::AppError, model::DocumentKind};

pub async fn resolve_existing_file(
    root: &Path,
    requested: &str,
    kind: DocumentKind,
) -> Result<PathBuf, AppError> {
    if requested.trim().is_empty() {
        return Err(AppError::InvalidInput("path is required".into()));
    }
    let relative = Path::new(requested);
    if relative.is_absolute()
        || relative.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        })
    {
        return Err(AppError::InvalidInput(
            "path must be relative and must not contain parent traversal".into(),
        ));
    }
    if relative.extension().and_then(|value| value.to_str()).map(str::to_ascii_lowercase)
        != Some(kind.expected_extension().to_owned())
    {
        return Err(AppError::InvalidInput(format!(
            "kind does not match .{} extension",
            kind.expected_extension()
        )));
    }
    let joined = root.join(relative);
    let canonical = tokio::fs::canonicalize(joined)
        .await
        .map_err(|error| match error.kind() {
            std::io::ErrorKind::NotFound => AppError::NotFound,
            _ => AppError::Internal,
        })?;
    if !canonical.starts_with(root) {
        return Err(AppError::InvalidInput("resolved path escapes DOC_ROOT".into()));
    }
    let metadata = tokio::fs::metadata(&canonical).await.map_err(|_| AppError::Internal)?;
    if !metadata.is_file() {
        return Err(AppError::InvalidInput("path does not name a regular file".into()));
    }
    Ok(canonical)
}
