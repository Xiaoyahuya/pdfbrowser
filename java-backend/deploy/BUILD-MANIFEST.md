# PDFBrowser 部署产物清单

- 构建日期：2026-08-09（Asia/Shanghai）
- 源码目录：`/home/lfp/pdfbrowser`
- 部署包：`deploy/pdfbrowser-server.jar`
- SHA-256：`275bce2d7b64f5358a195a94a74f55b1450da5283179118ae2dccbb2990b2d8e`
- 文件大小：`25359946` 字节
- Java：17
- 前端：Vue 3、VueFinder 4.7.3、PDF.js、markdown-it，已打入 JAR

## 本次变更

- 修复动态挂载后其他存储消失：VueFinder 始终显示全部已添加存储，不再只显示 `CONNECTED` 项。
- 挂载列表接口短暂失败时保留上一次成功结果；掉线存储保留入口，可在管理面板重连。
- 新增 WebDAV 动态只读挂载，支持通用 WebDAV、Nextcloud、ownCloud、SharePoint、Fastmail 和 rclone WebDAV。
- 新增 Google Drive 动态只读挂载，接受 `rclone authorize drive` 输出的 OAuth Token JSON，并支持根目录 ID。
- Google Drive 可选填写自有 OAuth Client ID / Client Secret，兼容 rclone 公共 Client ID 在 2026 年停用后的长期刷新。
- 保留 SMB / NAS 与 Tank 网络挂载；三种存储可同时存在。
- 固定 Google Drive 和动态挂载全部使用 `--vfs-cache-mode off`，不把远端文件内容写入服务器磁盘。
- 关闭跨 Range 预读，rclone 使用 1 MiB 到 16 MiB 的动态读取块、`buffer-size=0`。
- PDF.js Range 分块从 64 KiB 提升到 1 MiB，减少代理链路上的请求次数。
- Spring 增加内存目录缓存（10 秒）、安全路径缓存（60 秒）和挂载状态缓存（2 秒）。
- PDF/图片响应增加私有缓存、ETag、Last-Modified，并把服务端流式缓冲提升到 256 KiB。
- 目录元数据改为单次 `BasicFileAttributes` 读取，减少远程文件系统的重复 `stat`。

## 凭据与存储边界

- SMB/WebDAV 密码和 Google OAuth Token 用 AES-256-GCM 加密后保存在 `nas-runtime/mounts.json`。
- rclone 临时配置权限为 `0600`，挂载启动后立即删除；凭据不进入命令行、API 响应或正常日志。
- 服务器保存的只有加密挂载元数据、运行日志和内存缓存，不保存远端文件内容。
- 旧的 `/home/lfp/pdfbrowser/rclone-cache` 已清空；检查时目录占用 4 KiB，目录内没有文件。

## 自动化验证

- 前端 Vitest：18/18 通过。
- 前端 TypeScript 检查：通过。
- 前端 Vite 生产构建：通过，295 个模块完成转换。
- Spring Boot 测试：15/15 通过。
- WebDAV 真实协议测试：创建、同时显示 SMB + WebDAV、浏览根目录、卸载与清理全部通过。
- Google Drive 真实 OAuth Token 测试：创建只读挂载、浏览根目录、同时显示 SMB + Google Drive、卸载全部通过。
- 固定 Google Drive 与 SMB 的进程参数均确认包含 `vfs-cache-mode off`、`buffer-size 0`，且没有 `cache-dir`。

## 上线验证

- `pdfbrowser-vuefinder.service`：`active`
- `rclone-google-drive.service`：`active`
- `mihomo-rclone.service`：`active`
- `nginx`：`active`
- 公网 HTTPS 未认证返回 401，认证后首页返回 200。
- PDF Range 返回 206、正确的 `Content-Range` 和 `%PDF-` 文件头。
- Range 响应包含 `Cache-Control: private, max-age=300` 与 ETag。
- 目录首次读取实测约 0.884 秒，同路径内存缓存命中约 0.0047 秒。
- 1,000 字节公网 PDF Range 实测约 3.54 秒；主要耗时为 Google Drive 与代理链路，单次请求内部不再触发整文件缓存。

## 运行入口

- 公网：`https://110.42.101.86:18880`
- Spring：`127.0.0.1:18082`
- 固定 Google Drive：`/home/lfp/pdfbrowser/google-drive`
- 动态挂载运行目录：`/home/lfp/pdfbrowser/nas-runtime`
