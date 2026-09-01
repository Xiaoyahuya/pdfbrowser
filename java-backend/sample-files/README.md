# PDF & Markdown Browser 示例

这个目录可以直接作为 `PDFBROWSER_ROOT` 启动项目。

## 支持内容

- PDF 文件：在右侧使用浏览器原生 PDF 阅读器打开。
- Markdown 文件：转换为安全 HTML，原始 HTML默认不会执行。
- Markdown 相对图片：例如 `images/architecture.png` 或 `../images/result.png`。
- 其他文件：显示在列表中，可下载但不内联执行。

## 使用建议

1. 把 PDF、Markdown 和相关图片放在同一资料树中。
2. 文件名尽量表达主题和版本，不依赖绝对路径。
3. 不要把密码、密钥或私人目录配置成浏览根目录。

| 类型 | 示例操作 |
| --- | --- |
| PDF | 单击文件，在右侧预览 |
| Markdown | 单击文件，查看渲染结果 |
| 目录 | 单击进入，使用面包屑返回 |

