use std::{fs, path::PathBuf, time::{SystemTime, UNIX_EPOCH}};

use rust_pdf_content_engine::{model::DocumentKind, path_guard::resolve_existing_file};

fn temporary_root() -> PathBuf {
    let unique = SystemTime::now().duration_since(UNIX_EPOCH).expect("clock").as_nanos();
    let path = std::env::temp_dir().join(format!("rust-doc-engine-{unique}"));
    fs::create_dir_all(&path).expect("create temporary root");
    path.canonicalize().expect("canonical temporary root")
}

#[tokio::test]
async fn resolves_file_inside_root() {
    let root = temporary_root();
    fs::write(root.join("ok.md"), "# ok").expect("write fixture");
    let resolved = resolve_existing_file(&root, "ok.md", DocumentKind::Markdown)
        .await
        .expect("resolve inside root");
    assert!(resolved.starts_with(&root));
    fs::remove_dir_all(root).expect("remove temporary root");
}

#[tokio::test]
async fn rejects_parent_traversal_before_io() {
    let root = temporary_root();
    let error = resolve_existing_file(&root, "../secret.md", DocumentKind::Markdown)
        .await
        .expect_err("parent traversal must fail");
    assert!(error.to_string().contains("relative"));
    fs::remove_dir_all(root).expect("remove temporary root");
}
