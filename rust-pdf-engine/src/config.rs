use std::{
    env,
    net::SocketAddr,
    path::PathBuf,
    str::FromStr,
};

#[derive(Debug, Clone)]
pub struct Config {
    pub addr: SocketAddr,
    pub root: PathBuf,
    pub max_document_bytes: u64,
    pub analysis_concurrency: usize,
}

impl Config {
    pub fn from_env() -> Result<Self, String> {
        let addr = parse_env("RUST_ENGINE_ADDR", "127.0.0.1:8092")?;
        let configured_root = env::var_os("DOC_ROOT")
            .map(PathBuf::from)
            .unwrap_or(env::current_dir().map_err(|error| format!("read current directory: {error}"))?);
        let root = configured_root
            .canonicalize()
            .map_err(|error| format!("canonicalize DOC_ROOT {}: {error}", configured_root.display()))?;
        if !root.is_dir() {
            return Err(format!("DOC_ROOT is not a directory: {}", root.display()));
        }
        let max_document_bytes = parse_env("MAX_DOCUMENT_BYTES", "33554432")?;
        if max_document_bytes == 0 {
            return Err("MAX_DOCUMENT_BYTES must be greater than zero".into());
        }
        let default_concurrency = std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(1)
            .to_string();
        let analysis_concurrency = parse_env("ANALYSIS_CONCURRENCY", &default_concurrency)?;
        if analysis_concurrency == 0 {
            return Err("ANALYSIS_CONCURRENCY must be greater than zero".into());
        }
        Ok(Self { addr, root, max_document_bytes, analysis_concurrency })
    }
}

fn parse_env<T>(key: &str, fallback: &str) -> Result<T, String>
where
    T: FromStr,
    T::Err: std::fmt::Display,
{
    env::var(key)
        .unwrap_or_else(|_| fallback.to_owned())
        .parse::<T>()
        .map_err(|error| format!("parse {key}: {error}"))
}
