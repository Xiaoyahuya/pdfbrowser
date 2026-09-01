# Rust PDF/Markdown 内容引擎

这个服务接收相对路径，安全地定位文档，然后执行内容分析。它与 Go 任务协调服务配套：Go 决定“什么时候做、同时做多少、如何取消”，Rust 负责“怎样安全且高效地处理字节”。

配套教材：`Z:\文档\00_知识体系\深度剖析\13_Rust异步后端代码课`。

## 当前能力

- Markdown：统计标题、链接、代码块、行数、词数并生成短摘要；
- PDF：校验文件头，读取版本、字节数、近似页数和简单标题；
- 路径安全：拒绝绝对路径、`..`、符号链接逃逸与类型不匹配；
- 资源边界：最大文件大小和 CPU 分析并发数均可配置；
- 异步边界：文件读取使用 Tokio，CPU 分析进入 `spawn_blocking`。

PDF 页数是通过对象标记得到的轻量近似值，不代替完整 PDF 解析器。项目刻意保留这个边界，便于先学清所有权、异步运行时和系统调用，再按验收标准接入成熟 PDF 库。

当前路径防护适用于文档目录不被不可信用户并发改写的部署。若攻击者能在校验和打开之间替换目录项，生产版应进一步采用 capability-based 文件 API 或 Linux `openat2` 约束，把“校验路径”和“打开文件”合成一个内核级安全操作。

## 运行

要求 Rust 1.97.1+。

```bash
cargo fmt --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test
DOC_ROOT=/srv/documents cargo run
```

Windows PowerShell：

```powershell
$env:DOC_ROOT='Z:\文档'
cargo run
```

环境变量：

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `RUST_ENGINE_ADDR` | `127.0.0.1:8092` | 监听地址 |
| `DOC_ROOT` | 当前目录 | 允许读取的根目录 |
| `MAX_DOCUMENT_BYTES` | `33554432` | 单文件上限，默认 32 MiB |
| `ANALYSIS_CONCURRENCY` | CPU 核数 | CPU 分析并发上限 |
| `RUST_LOG` | `rust_pdf_content_engine=info,tower_http=info` | 日志过滤 |

调用示例：

```bash
curl -sS http://127.0.0.1:8092/v1/analyze \
  -H 'Content-Type: application/json' \
  -d '{"path":"manual/intro.md","kind":"markdown"}'
```

## 关键调用链

```text
Axum Router
  -> api::analyze
  -> Engine::analyze
  -> path_guard::resolve_existing_file
  -> tokio::fs::read
  -> tokio::task::spawn_blocking
  -> analyze_markdown / analyze_pdf
  -> JSON response
```

本机当前没有 Rust 工具链，所以没有伪造编译或测试成功记录。安装工具链后，以本页四条质量命令为第一道门禁。
