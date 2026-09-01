# Go PDF 任务协调服务

这是 PDF/Markdown Browser 的异步任务层：Spring 继续负责文件浏览与 Range 下载；本服务负责接收“分析文档”任务、限制并发、调用 Rust 内容引擎并保存任务状态。

配套教材：`Z:\文档\00_知识体系\深度剖析\12_Go后端与运行时代码课`。

## 为什么用这个项目学 Go

它不是语法演示，而是一个能稳定制造并发问题的小型后端：

- 有界 `channel` 决定系统能否背压，而不是无限接收后把内存耗尽；
- 固定数量 worker 让 goroutine、G-M-P 调度和 Linux 线程关系可观察；
- `context.Context` 把客户端取消和下游 HTTP 取消串起来；
- `sync.RWMutex` 保护任务表，便于学习竞态、锁竞争和内存模型；
- `/debug/pprof/`、`/metrics` 为 CPU、堆、阻塞、goroutine 泄漏留下证据入口。

## 运行

要求 Go 1.26+，以及已启动的 Rust 内容引擎。

```bash
go test ./...
go run ./cmd/server
```

环境变量：

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `GO_TASK_ADDR` | `:8091` | Go 服务监听地址 |
| `RUST_ENGINE_URL` | `http://127.0.0.1:8092` | Rust 内容引擎地址 |
| `TASK_WORKERS` | `4` | 固定 worker 数 |
| `TASK_QUEUE_CAPACITY` | `64` | 等待队列容量 |
| `TASK_TIMEOUT` | `30s` | 单任务下游超时 |

## 最短调用链

```text
POST /v1/tasks
  -> api.Server.createTask
  -> task.Manager.Submit
  -> queue channel
  -> task.Manager.worker
  -> RustClient.Process
  -> POST Rust /v1/analyze
  -> Store.Complete
```

提交任务：

```bash
curl -sS http://127.0.0.1:8091/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"path":"manual/intro.md","kind":"markdown"}'
```

查询与取消：

```bash
curl -sS http://127.0.0.1:8091/v1/tasks/<task-id>
curl -sS -X DELETE http://127.0.0.1:8091/v1/tasks/<task-id>
```

## 与现有项目的边界

- Vue：发起任务、轮询结果、展示状态。
- Spring：鉴权、目录浏览、PDF Range、统一网关。
- Go：任务生命周期、并发上限、取消、重试策略与可观测性。
- Rust：安全解析路径、读取文档、CPU 密集分析。

本机当前没有 Go 工具链，因此源码已做静态结构校验，但没有伪造编译成功记录。安装 Go 后先运行 `go test ./...`。
