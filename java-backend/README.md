# PDFBrowser Java 后端

面向学习资料和书籍的在线文件管理与阅读服务。前端直接使用
[VueFinder 4.7.3](https://github.com/n1crack/vuefinder)，后端使用 Spring Boot，文件来自
Google Drive 的 rclone 挂载目录，也可以从页面挂载 SMB NAS。

## 功能

- VueFinder 官方文件管理界面：目录树、列表/网格、面包屑、搜索、上传、下载、历史导航和全屏。
- 界面只暴露“学习资料”和“书籍”两个入口。
- “阅读资料”在界面上归入“书籍”，无需移动 Google Drive 中的物理文件。
- PDF 使用 PDF.js 分段加载和 Canvas 渲染，支持大文件、翻页和缩放。
- Markdown 使用 markdown-it 安全渲染，支持相对链接和相对图片。
- Spring 后端限制所有路径必须位于配置的资料根目录内。
- Google Drive 当前为只读授权；浏览和下载可用，上传接口会返回明确的只读错误。
- 从“挂载 NAS”面板输入 IP、共享名、账号和密码，将 SMB 共享映射成 VueFinder 独立存储。
- NAS 支持直接连接，或经本机 Mihomo 的 Tank OpenVPN 出口连接远端 `192.168.1.x` 网络。
- NAS 挂载为只读，支持目录、搜索、下载、PDF Range 读取和 Markdown 预览。
- 整站由 HTTPS 与 Nginx Basic Auth 保护；Spring 只监听回环地址。

## 结构

```text
pdfbrowser/
├── vue-frontend/       # Vue 3、VueFinder、PDF.js、markdown-it
├── java-backend/       # 当前目录
│   ├── src/            # Spring Boot 文件 API
│   ├── deploy/         # systemd、Nginx、rclone 与部署脚本
│   ├── sample-files/   # 本地开发示例
│   └── README.md
├── go-orchestrator/    # Go 任务协调服务
└── rust-pdf-engine/    # Rust 内容处理引擎
```

## 前端开发

需要 Node.js 22.18+：

```bash
cd ../vue-frontend
npm ci
npm test
npm run dev
```

Vite 默认监听 `http://localhost:5174`，通过 `PDFBROWSER_API_TARGET` 指定 Spring 地址：

```bash
PDFBROWSER_API_TARGET=http://localhost:8082 npm run dev
```

## 后端开发

需要 Java 17+ 和 Maven：

```bash
cd /home/lfp/pdfbrowser/java-backend
PDFBROWSER_ROOT="$(cd sample-files && pwd)" ./mvnw spring-boot:run
```

默认地址为 `http://localhost:8082`。

## 主要配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PDFBROWSER_ROOT` | `~/pdfbrowser-files` | 唯一允许访问的文件根目录 |
| `PDFBROWSER_READ_ONLY` | `true` | 是否禁止上传和其他写操作 |
| `PDFBROWSER_MAX_UPLOAD_BYTES` | `104857600` | 上传文件大小上限 |
| `PDFBROWSER_MAX_MARKDOWN_BYTES` | `2097152` | Markdown 最大预览字节数 |
| `PDFBROWSER_SEARCH_MAX_DEPTH` | `8` | 递归搜索最大深度 |
| `PDFBROWSER_SEARCH_MAX_RESULTS` | `200` | 搜索结果上限 |
| `PDFBROWSER_NAS_RUNTIME` | `/home/lfp/pdfbrowser/nas-runtime` | NAS 挂载、加密记录和日志目录 |
| `PDFBROWSER_NAS_CREDENTIAL_KEY` | 无 | AES-256-GCM 主密钥；部署脚本自动生成 |
| `PDFBROWSER_NAS_ALLOW_PUBLIC_HOSTS` | `false` | 是否允许把公网 IP 当作 SMB 目标 |
| `PDFBROWSER_NAS_SOCKS_HOST` | `127.0.0.1` | Tank/Mihomo SOCKS5 地址 |
| `PDFBROWSER_NAS_SOCKS_PORT` | `7897` | Tank/Mihomo SOCKS5 端口 |
| `PORT` | `8082` | Spring 端口 |

## API

| 请求 | 用途 |
| --- | --- |
| `GET /api/files?path=` | 列出目录 |
| `GET /api/files/search?path=&q=` | 递归搜索 |
| `GET /api/files/markdown?path=` | 读取 Markdown |
| `GET /api/files/raw?path=` | 读取 PDF、图片或普通文件，支持 Range |
| `GET /api/files/raw?path=&download=true` | 下载文件 |
| `GET /api/files/capabilities` | 查询存储是否可写及上传上限 |
| `POST /api/files/upload?path=` | 上传文件；只读模式返回 503 |
| `GET /api/nas/mounts` | 查询 NAS 挂载及连接状态，不返回密码 |
| `POST /api/nas/mounts` | 验证 SMB 后创建只读挂载；仅允许 HTTPS |
| `POST /api/nas/mounts/{id}/connect` | 重新连接已保存的 NAS |
| `DELETE /api/nas/mounts/{id}` | 卸载并删除该 NAS 的加密凭据 |
| `GET /api/nas/mounts/{id}/files` | 浏览 NAS 文件；其余 search/markdown/raw 与主文件 API 对应 |

所有 `path` 都相对于 `PDFBROWSER_ROOT`，绝对路径、越界路径和符号链接逃逸都会被拒绝。
NAS 文件路径则必须位于对应挂载点内。NAS 变更请求还必须携带同源前端设置的
`X-PDFBrowser-Action: nas-mount`，用于阻止跨站表单伪造。

## NAS 凭据与 Tank 网络

页面提交 NAS 密码后，服务器先通过 SMB 列目录验证 IP、共享名和凭据，验证成功才创建
FUSE 挂载。密码使用随机 IV 的 AES-256-GCM 加密后保存；主密钥只存在于权限为 `0600`
的环境文件。传给 rclone 的兼容密文配置是临时文件，rclone 启动后立即删除，密码不会
出现在命令行参数、API 响应或应用日志中。

勾选“经 Tank 网络连接”时，Java 在 `127.0.0.1` 创建临时 TCP 中继，通过 Mihomo SOCKS5
把 SMB 的 445 连接送往目标 IP。Tank 配置中的 `192.168.1.0/24 -> Tank VPN` 规则负责把
远端家庭网段交给 OpenVPN；取消勾选时则由云服务器直接连接 NAS。

## VueFinder 集成

前端安装官方 `vuefinder` npm 包，没有复刻它的页面。`vuefinderDriver.ts` 负责把 VueFinder
的 Driver 契约转换为现有 Spring API，并把界面虚拟目录映射到 Google Drive 的真实目录。
VueFinder 使用 MIT 许可证，许可文本保存在 `../vue-frontend/third-party/vuefinder-LICENSE`。

## 当前部署

- 公网入口：`https://110.42.101.86:18880`，由 Nginx Basic Auth 保护。
- 旧的 `http://110.42.101.86:18880` 会自动跳转到 HTTPS。
- 当前使用带 IP SAN 的自签名证书，首次访问需要确认浏览器的证书提示。
- Spring Boot：仅监听 `127.0.0.1:18082`。
- Google Drive：只读挂载到 `/home/lfp/pdfbrowser/google-drive`。
- rclone 代理：Mihomo 仅监听 `127.0.0.1:7897`。
- NAS 运行目录：`/home/lfp/pdfbrowser/nas-runtime`，权限为 `0700`。
- 如果后续绑定域名，应把自签名证书替换为受信任的 ACME/Let's Encrypt 证书。

## 动态远程存储

“挂载存储”面板支持三种只读来源：

- SMB / NAS：填写 NAS IP、共享名、账号和密码；可选 Tank 网络。
- WebDAV：填写 HTTPS 地址、账号、应用专用密码和服务类型，适用于 Nextcloud、ownCloud、坚果云、AList 等。
- Google Drive：在自己的电脑执行 `rclone authorize drive`，把输出的 Token JSON 粘贴到面板；可选根目录 ID。

所有凭据都经 HTTPS 传输，并用 AES-256-GCM 加密后保存在 `nas-runtime/mounts.json`。临时 rclone 配置在进程启动后立即删除。固定 Google Drive 和动态存储统一使用 `--vfs-cache-mode off`，不会把远端文件内容写入服务器磁盘；仅保留内存中的短时目录、路径和 Markdown 缓存。

已添加的存储入口与连接状态分离：即使某个存储掉线，或者挂载列表接口短暂失败，其他存储仍保留在 VueFinder 左侧。点击掉线存储会显示连接错误，可在面板中重连或卸载。

## 读取性能

- PDF.js 使用 1 MiB Range 分块，显著减少高延迟代理上的请求次数。
- HTTP Range 响应带 5 分钟私有缓存、ETag 和 Last-Modified，浏览器可复用已读取片段。
- Spring 对目录列表缓存 10 秒、已验证路径缓存 60 秒；缓存仅在内存中。
- rclone 关闭磁盘 VFS 缓存和跨 Range 预读，避免为了 PDF 尾部的小片段下载整份文件。
