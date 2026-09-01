use rust_pdf_content_engine::{app, config::Config};
use tokio::net::TcpListener;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "rust_pdf_content_engine=info,tower_http=info".into()),
        )
        .init();
    let config = Config::from_env().map_err(|message| std::io::Error::other(message))?;
    let listener = TcpListener::bind(config.addr).await?;
    info!(addr = %config.addr, root = %config.root.display(), "rust content engine listening");
    axum::serve(listener, app(&config))
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

async fn shutdown_signal() {
    if let Err(error) = tokio::signal::ctrl_c().await {
        tracing::error!(%error, "failed to install shutdown signal handler");
    }
}
