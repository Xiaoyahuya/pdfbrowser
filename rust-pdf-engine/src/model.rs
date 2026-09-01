use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DocumentKind {
    Markdown,
    Pdf,
}

impl DocumentKind {
    pub fn expected_extension(self) -> &'static str {
        match self {
            Self::Markdown => "md",
            Self::Pdf => "pdf",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AnalyzeRequest {
    pub path: String,
    pub kind: DocumentKind,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalyzeResponse {
    pub path: String,
    pub kind: &'static str,
    pub byte_size: usize,
    pub analysis_mode: &'static str,
    pub details: AnalysisDetails,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum AnalysisDetails {
    Markdown(MarkdownAnalysis),
    Pdf(PdfAnalysis),
}

#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct MarkdownAnalysis {
    pub lines: usize,
    pub words: usize,
    pub headings: usize,
    pub links: usize,
    pub fenced_code_blocks: usize,
    pub excerpt: String,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PdfAnalysis {
    pub version: String,
    pub approximate_pages: usize,
    pub title: Option<String>,
}
