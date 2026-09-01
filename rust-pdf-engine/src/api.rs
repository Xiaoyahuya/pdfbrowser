/** 
    Arc(Atomic Reference Counted),多个线程可以共同拥有一份数据
    AtomicU64,让多个线程安全地读写一个 u64，不需要 Mutex
*/
use std::sync::{
    Arc, 
    atomic::{AtomicU64, Ordering},
};

/** 
    Json：把 Rust 数据作为 JSON 返回，或者从 JSON 请求体里解析数据
    State：从 Axum 应用里拿“共享状态”
    Serialize：允许 Rust 结构体被转换成 JSON
*/
use axum::{Json, extract::State};
use serde::Serialize;

use crate::{engine::Engine, error::AppError, model::{AnalyzeRequest, AnalyzeResponse}};

#[derive(Clone)]
pub struct AppState {
    pub engine: Arc<Engine>,
    pub metrics: Arc<Metrics>,
}

#[derive(Default)]
pub struct Metrics {
    started: AtomicU64,
    succeeded: AtomicU64,
    failed: AtomicU64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthResponse {
    status: &'static str,
    analyses_started: u64,
    analyses_succeeded: u64,
    analyses_failed: u64,
}

pub async fn health(State(state): State<AppState>) -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        analyses_started: state.metrics.started.load(Ordering::Relaxed),
        analyses_succeeded: state.metrics.succeeded.load(Ordering::Relaxed),
        analyses_failed: state.metrics.failed.load(Ordering::Relaxed),
    })
}

pub async fn analyze(
    State(state): State<AppState>,
    Json(request): Json<AnalyzeRequest>,
) -> Result<Json<AnalyzeResponse>, AppError> {
    state.metrics.started.fetch_add(1, Ordering::Relaxed);
    match state.engine.analyze(request).await {
        Ok(response) => {
            state.metrics.succeeded.fetch_add(1, Ordering::Relaxed);
            Ok(Json(response))
        }
        Err(error) => {
            state.metrics.failed.fetch_add(1, Ordering::Relaxed);
            Err(error)
        }
    }
}
