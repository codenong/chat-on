# assets/chat/ —— 上游 chat-demo 构建产物（vendored）

本目录存放固定 commit 的官方 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo)
构建产物（`dist/` 内容）。聊天 UI、IndexedDB 存储、API 请求全部来自上游，壳层不修改。

- `COMMIT.txt`：记录本目录产物对应的上游 commit（构建脚本自动写入）。
- `index.html`：入口页面，加载路径为
  `https://appassets.androidplatform.net/assets/chat/index.html`。
- `LICENSE`：上游 MIT 许可证副本，分发时必须保留。

## 当前 vendored 版本

- 上游 commit：`5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c`（v0.1.1，2026-11-13）
- 核验/构建日期：2026-11-18

## 如何重新 vendored（升级或首次）

运行仓库根目录的脚本（需要 Node.js 22.12+ 与 pnpm，或按 README 使用 Releases 压缩包）：

```sh
scripts/fetch_chat_demo.sh      # 克隆固定 commit、构建、拷贝 dist、写 COMMIT.txt
```

升级后必须按 `docs/01_集成指南.md` 重新核对 `assets/inject/upstream-config.js`
的三项适配契约（localStorage 键名、导入入口选择器、导出触发方式）。
