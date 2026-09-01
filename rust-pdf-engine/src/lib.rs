pub mod api;
pub mod config;
pub mod engine;
pub mod error;
pub mod model;
pub mod path_guard;

use std::sync::Arc;

use api::{AppState, Metrics};
use axum::{Router, extract::DefaultBodyLimit, routing::{get, post}};
use config::Config;
use engine::Engine;
use tower_http::trace::TraceLayer;

pub fn app(config: &Config) -> Router {
    let state = AppState {
        engine: Arc::new(Engine::new(
            config.root.clone(),
            config.max_document_bytes,
            config.analysis_concurrency,
        )),
        metrics: Arc::new(Metrics::default()),
    };
    Router::new()
        .route("/health", get(api::health))
        .route("/v1/analyze", post(api::analyze))
        .layer(DefaultBodyLimit::max(64 * 1024))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}
