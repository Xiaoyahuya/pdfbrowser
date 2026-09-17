# PDFBrowser

PDFBrowser 是一个面向学习资料、书籍和文档管理的本地/在线浏览器项目。它把“文件浏览、PDF 阅读、Markdown 预览、NAS/Google Drive 远程存储、内容分析任务”整合成一个统一系统，适合学习分层架构、前后端联调、异步任务和安全文件访问。

本仓库包含四个核心模块：

- `vue-frontend/`：Vue 3 前端，基于 VueFinder 提供文件管理和阅读界面
- `java-backend/`：Spring Boot 后端，负责基础 API、文件访问、安全校验和 NAS 挂载
- `go-orchestrator/`：Go 任务协调层，负责异步任务调度与并发控制
- `rust-pdf-engine/`：Rust 内容分析引擎，负责 PDF/Markdown 安全读取与内容分析

---

## 1. 项目概览

这个项目的目标是让用户可以：

- 浏览和搜索本地文档库
- 预览 PDF、Markdown 和普通文件
- 访问受控目录中的内容，而不是直接暴露整个文件系统
- 通过 NAS 或远程存储扩展文件来源
- 在后台分析文档内容，形成任务状态与结果回调

从结构上看，项目采用了“多语言分层”的设计：

- 前端负责交互与展示
- Java 后端负责统一 API 和安全边界
- Go 负责任务编排和并发控制
- Rust 负责 CPU 密集型内容处理与安全路径解析

这样可以把不同关注点拆开，便于学习各语言生态、调用链和系统边界。

---

## 2. 架构说明

```text
浏览器 / Vue 前端
        |
        v
Spring Boot API
        |
        +--> 文件浏览、目录安全校验、读取控制
        |
        +--> NAS / SMB / WebDAV / Google Drive 入口
        |
        v
Go Orchestrator
        |
        +--> 任务队列、worker、超时控制、取消处理
        |
        v
Rust PDF/Markdown Engine
        |
        +--> 安全路径解析
        +--> PDF/Markdown 读取与摘要分析
        +--> 任务结果返回
```

核心职责分工：

- `vue-frontend`：页面、交互、文件框架、PDF/Markdown 展示
- `java-backend`：统一入口、路由、权限、Range 下载、NAS 挂载与存储管理
- `go-orchestrator`：异步任务、状态管理、后台处理协调
- `rust-pdf-engine`：底层文档处理，安全且高性能

---

## 3. 目录结构

```text
pdfbrowser/
├── README.md
├── compose.yaml               # PostgreSQL 容器配置
├── start-dev.sh               # 一键开发启动脚本
├── go-orchestrator/           # Go 任务协调服务
│   ├── cmd/
│   ├── internal/
│   └── README.md
├── java-backend/              # Spring Boot 后端
│   ├── src/
│   ├── sample-files/
│   ├── deploy/
│   ├── mvnw
│   └── README.md
├── rust-pdf-engine/           # Rust 内容分析引擎
│   ├── src/
│   ├── tests/
│   └── README.md
├── vue-frontend/              # Vue 3 前端
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── vite.config.ts
├── docs/
├── diagrams/
└── temp.md
```

---

## 4. 运行环境要求

开发环境依赖：

- Docker + Docker Compose
- Node.js 22.18+（前端要求）
- Java 17+（后端要求）
- Go 1.26+（任务协调服务要求）
- Rust 1.97.1+（内容分析引擎要求）

本项目中已提供一键开发脚本：

```bash
cd /home/lfp/Projects/pdfbrowser
./start-dev.sh
```

脚本会自动执行：

- 检查依赖是否可用
- 启动或复用 PostgreSQL 容器
- 启动 Rust 内容分析引擎
- 启动 Go 任务协调服务
- 启动 Spring Boot 后端
- 启动 Vue 前端开发服务器

启动成功后，通常会有以下地址：

- Vue 前端：`http://127.0.0.1:5174`
- Spring Boot：`http://127.0.0.1:8082`
- Go 服务：`http://127.0.0.1:8091`
- Rust 引擎：`http://127.0.0.1:8092`

> `start-dev.sh` 还支持设置 `PDFBROWSER_ROOT`、`PDFBROWSER_VITE_PORT`、`PDFBROWSER_DB_PORT` 等环境变量，便于调整开发环境。

---

## 5. 快速开始

### 5.1 一键启动

```bash
cd /home/lfp/Projects/pdfbrowser
./start-dev.sh
```

如果你还没有安装前端依赖，需要先执行：

```bash
cd /home/lfp/Projects/pdfbrowser/vue-frontend
npm ci
```

### 5.2 单独启动前端

```bash
cd /home/lfp/Projects/pdfbrowser/vue-frontend
npm run dev -- --host 127.0.0.1 --port 5174
```

### 5.3 单独启动后端

```bash
cd /home/lfp/Projects/pdfbrowser/java-backend
PDFBROWSER_ROOT="$(cd sample-files && pwd)" ./mvnw spring-boot:run
```

### 5.4 单独启动 Go 服务

```bash
cd /home/lfp/Projects/pdfbrowser/go-orchestrator
RUST_ENGINE_URL=http://127.0.0.1:8092 GO_TASK_ADDR=127.0.0.1:8091 go run ./cmd/server
```

### 5.5 单独启动 Rust 引擎

```bash
cd /home/lfp/Projects/pdfbrowser/rust-pdf-engine
DOC_ROOT=/path/to/your/documents cargo run
```

---

## 6. 核心功能

### 文件管理

- 目录树和搜索
- 文件列表/网格展示
- 面包屑导航和历史记录
- 上传、下载、读取和 Range 请求
- 目录访问限制在允许的根目录内

### PDF/Markdown 处理

- PDF 分段读取与渲染
- 大文件范围下载
- Markdown 安全渲染
- 相对链接和图片资源处理
- 文档内容摘要和分析

### 远程存储支持

- NAS / SMB 挂载
- WebDAV
- Google Drive
- 只读/动态挂载模式

### 安全与边界控制

- 路径必须位于配置根目录下
- 绝对路径、`..` 跳转、符号链接逃逸会被拒绝
- 文件类型和大小受到限制
- API 访问中有额外的环境变量和安全约束

---

## 7. 主要配置项

下面是常用环境变量，更多细节请参考各子模块的 README：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PDFBROWSER_ROOT` | `java-backend/sample-files` | 文档根目录 |
| `PDFBROWSER_DB_HOST` | `127.0.0.1` | PostgreSQL 地址 |
| `PDFBROWSER_DB_PORT` | `5432` | PostgreSQL 端口 |
| `PDFBROWSER_DB_NAME` | `postgres` | 默认数据库名 |
| `PDFBROWSER_DB_USER` | `postgres` | 数据库用户 |
| `PDFBROWSER_DB_PASSWORD` | `123456` | 本地开发密码 |
| `RUST_ENGINE_ADDR` | `127.0.0.1:8092` | Rust 服务监听地址 |
| `GO_TASK_ADDR` | `127.0.0.1:8091` | Go 服务监听地址 |
| `PORT` | `8082` | Spring Boot 端口 |
| `PDFBROWSER_VITE_HOST` | `127.0.0.1` | 前端监听地址 |
| `PDFBROWSER_VITE_PORT` | `5174` | 前端监听端口 |

---

## 8. 子模块说明

### `vue-frontend/`

前端采用 Vue 3 + Vite，依赖包括：

- `vuefinder`：文件浏览器界面
- `pdfjs-dist`：PDF 渲染
- `markdown-it`：Markdown 渲染
- `tailwindcss`：样式系统

主要脚本：

```bash
npm run dev
npm run build
npm test
```

### `java-backend/`

后端基于 Spring Boot，负责：

- API 路由
- 文件系统访问控制
- 远程存储挂载与连接
- 文件读取和下载能力
- 安全路径限制

### `go-orchestrator/`

Go 层负责：

- 任务排队
- worker 并发控制
- 超时与取消处理
- 调用 Rust 分析引擎
- 测量跟踪和状态更新

### `rust-pdf-engine/`

Rust 层负责：

- 安全解析路径
- 读取 PDF 与 Markdown
- 文档摘要/统计分析
- 处理 CPU 密集型计算

---

## 9. 开发建议

为了保持项目可维护和学习价值，建议按下面的顺序阅读：

1. `start-dev.sh`：了解全部开发流程和默认环境变量
2. `java-backend/README.md`：理解后端 API 与安全边界
3. `go-orchestrator/README.md`：理解任务队列与 worker 模型
4. `rust-pdf-engine/README.md`：理解安全路径与内容分析
5. `vue-frontend/package.json` 和 `src/`：理解前端页面和调用方式

如果你想深入学习系统设计，重点关注以下几个点：

- 路径安全：避免越权访问和目录逃逸
- 异步任务：Go 层如何限制并发与处理取消
- 前后端契约：Spring API 与 Vue 交互方式
- 内容处理：PDF 和 Markdown 的读取与分析边界

---

## 10. 备注

这个仓库更适合作为“分层实战项目”来学习，而不是一个简单的单服务应用。它把多种技术混合在一起：

- 前端：Vue 3
- 后端：Spring Boot
- 任务协调：Go
- 内容引擎：Rust
- 数据库：PostgreSQL
- 容器：Docker Compose

如果你是在学习架构设计、异步系统、安全 I/O、或前后端联动，这个项目都很适合作为练手工程。

---

## 11. 相关文档

- [java-backend/README.md](java-backend/README.md)
- [go-orchestrator/README.md](go-orchestrator/README.md)
- [rust-pdf-engine/README.md](rust-pdf-engine/README.md)
- [vue-frontend/package.json](vue-frontend/package.json)

如果你需要，我也可以继续把这个仓库 README 再细化成“中文概览版 / 英文版 / 面向部署版”三种风格中的任意一种。
