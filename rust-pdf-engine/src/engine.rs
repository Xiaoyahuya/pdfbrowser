use std::{path::PathBuf, sync::Arc};

use tokio::io::AsyncReadExt;
use tokio::sync::Semaphore;

use crate::{
    error::AppError,
    model::{
        AnalysisDetails, AnalyzeRequest, AnalyzeResponse, DocumentKind, MarkdownAnalysis, PdfAnalysis,
    },
    path_guard::resolve_existing_file,
};

#[derive(Clone)]
pub struct Engine {
    root: PathBuf,
    max_document_bytes: u64,
    permits: Arc<Semaphore>,
}

impl Engine {
    pub fn new(root: PathBuf, max_document_bytes: u64, concurrency: usize) -> Self {
        Self { root, max_document_bytes, permits: Arc::new(Semaphore::new(concurrency)) }
    }

    pub async fn analyze(&self, request: AnalyzeRequest) -> Result<AnalyzeResponse, AppError> {
        let path = resolve_existing_file(&self.root, &request.path, request.kind).await?;
        let metadata = tokio::fs::metadata(&path).await.map_err(|_| AppError::Internal)?;
        if metadata.len() > self.max_document_bytes {
            return Err(AppError::TooLarge);
        }

        // The owned permit follows this job into the blocking pool and provides hard backpressure.
        let permit = self.permits.clone().acquire_owned().await.map_err(|_| AppError::Internal)?;
        // Limit the read itself instead of trusting a racy metadata check.
        let file = tokio::fs::File::open(path).await.map_err(|_| AppError::Internal)?;
        let mut reader = file.take(self.max_document_bytes.saturating_add(1));
        let mut bytes = Vec::new();
        reader.read_to_end(&mut bytes).await.map_err(|_| AppError::Internal)?;
        if bytes.len() as u64 > self.max_document_bytes {
            return Err(AppError::TooLarge);
        }
        let byte_size = bytes.len();
        let kind = request.kind;
        let details = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            analyze_bytes(kind, &bytes)
        })
        .await
        .map_err(|_| AppError::Internal)??;

        Ok(AnalyzeResponse {
            path: request.path,
            kind: match kind {
                DocumentKind::Markdown => "markdown",
                DocumentKind::Pdf => "pdf",
            },
            byte_size,
            analysis_mode: match kind {
                DocumentKind::Markdown => "text_structure",
                DocumentKind::Pdf => "lightweight_metadata",
            },
            details,
        })
    }
}

fn analyze_bytes(kind: DocumentKind, bytes: &[u8]) -> Result<AnalysisDetails, AppError> {
    match kind {
        DocumentKind::Markdown => analyze_markdown(bytes).map(AnalysisDetails::Markdown),
        DocumentKind::Pdf => analyze_pdf(bytes).map(AnalysisDetails::Pdf),
    }
}

pub(crate) fn analyze_markdown(bytes: &[u8]) -> Result<MarkdownAnalysis, AppError> {
    let source = std::str::from_utf8(bytes)
        .map_err(|_| AppError::Malformed("Markdown must be UTF-8".into()))?;
    let mut headings = 0;
    let mut links = 0;
    let mut fenced_code_blocks = 0;
    let mut inside_fence = false;
    let mut excerpt_parts = Vec::new();

    for line in source.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            if !inside_fence {
                fenced_code_blocks += 1;
            }
            inside_fence = !inside_fence;
            continue;
        }
        if !inside_fence {
            if trimmed.starts_with('#') && trimmed.trim_start_matches('#').starts_with(' ') {
                headings += 1;
            }
            links += count_markdown_links(trimmed);
            if !trimmed.is_empty() && !trimmed.starts_with('#') && excerpt_parts.len() < 6 {
                excerpt_parts.push(trimmed);
            }
        }
    }
    let excerpt = excerpt_parts.join(" ").chars().take(240).collect();
    Ok(MarkdownAnalysis {
        lines: source.lines().count(),
        words: source.split_whitespace().count(),
        headings,
        links,
        fenced_code_blocks,
        excerpt,
    })
}

fn count_markdown_links(line: &str) -> usize {
    let bytes = line.as_bytes();
    let mut cursor = 0;
    let mut count = 0;
    while cursor + 3 < bytes.len() {
        if bytes[cursor] == b']' && bytes[cursor + 1] == b'(' {
            count += 1;
            cursor += 2;
        } else {
            cursor += 1;
        }
    }
    count
}

pub(crate) fn analyze_pdf(bytes: &[u8]) -> Result<PdfAnalysis, AppError> {
    if !bytes.starts_with(b"%PDF-") {
        return Err(AppError::Malformed("missing %PDF- header".into()));
    }
    let version = bytes
        .get(5..8)
        .and_then(|value| std::str::from_utf8(value).ok())
        .unwrap_or("unknown")
        .to_owned();
    let approximate_pages = count_pdf_page_objects(bytes);
    let title = extract_pdf_literal(bytes, b"/Title (");
    Ok(PdfAnalysis { version, approximate_pages, title })
}

fn count_pdf_page_objects(bytes: &[u8]) -> usize {
    const NEEDLE: &[u8] = b"/Type /Page";
    bytes.windows(NEEDLE.len() + 1).filter(|window| {
        window.starts_with(NEEDLE) && window[NEEDLE.len()] != b's'
    }).count()
}

fn extract_pdf_literal(bytes: &[u8], prefix: &[u8]) -> Option<String> {
    let start = bytes.windows(prefix.len()).position(|window| window == prefix)? + prefix.len();
    let rest = bytes.get(start..)?;
    let end = rest.iter().position(|byte| *byte == b')')?;
    let value = std::str::from_utf8(&rest[..end]).ok()?.trim();
    (!value.is_empty()).then(|| value.chars().take(200).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn markdown_statistics_are_explainable() {
        let source = b"# Title\n\nRead [one](a) and [two](b).\n\n```rust\nfn main() {}\n```\n";
        let result = analyze_markdown(source).expect("valid markdown");
        assert_eq!(result.headings, 1);
        assert_eq!(result.links, 2);
        assert_eq!(result.fenced_code_blocks, 1);
        assert!(result.excerpt.starts_with("Read"));
    }

    #[test]
    fn pdf_pages_dictionary_is_not_counted_as_page() {
        let source = b"%PDF-1.7\n/Type /Pages\n/Type /Page\n/Type /Page\n/Title (Guide)";
        let result = analyze_pdf(source).expect("valid lightweight pdf");
        assert_eq!(result.approximate_pages, 2);
        assert_eq!(result.title.as_deref(), Some("Guide"));
    }
}
