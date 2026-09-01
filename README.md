# PDFBrowser

本目录按 `90天学习科研与项目计划.md` 的跨语言主项目边界整理：Vue 负责用户界面，Spring 负责业务与安全边界，Go 负责异步任务协调，Rust 负责 PDF/文档内容处理。

VS Code Remote SSH 开发请直接打开根目录下的 `pdfbrowser.code-workspace`，四个子项目会分别启用 Volar、JDT LS、gopls 和 rust-analyzer。

## 顶级目录

四个实际源码目录统一使用 kebab-case 命名：

```text
pdfbrowser/
├── vue-frontend/          # Vue 用户端和管理端
├── java-backend/          # Spring API、业务、安全、部署脚本和样例
├── go-orchestrator/       # Go 队列、Worker、租约、取消、重试和恢复
├── rust-pdf-engine/       # Rust PDF/Markdown 内容处理与资源边界
├── google-drive/          # 只读 Google Drive 运行挂载，不是源码
└── nas-runtime/           # 动态 NAS 挂载状态、凭据和日志，不是源码
```

## 组件边界

- Vue 只负责展示和交互，不验证密码、不决定权限，也不直接访问 Go/Rust。
- Spring 是唯一的登录、授权、租户隔离和业务数据真相边界；Go/Rust 不读取密码、不绕过 Spring 修改业务表。
- Go 处理 Outbox 事件、任务状态机、有限队列、租约、超时、取消、重试和异步用量/通知。
- Rust 处理文档字节、资源上限、取消和结构化分析结果，不负责登录、授权、队列或业务数据库。

主链为：

```text
Vue -> Spring -> Outbox -> Go(pdf-orchestrator) -> Rust(pdf-engine)
    -> Spring 持久化状态/用量/审计 -> Vue 查询或 SSE/WebSocket 展示
```

系统级 `pdfbrowser-vuefinder.service` 与 `rclone-google-drive.service` 已改为直接使用 `java-backend/deploy`，旧 `pdfbrowser_standalone` 路径已删除。

Google Drive 与 NAS 运行目录保持原路径，避免重挂载和凭据迁移。
