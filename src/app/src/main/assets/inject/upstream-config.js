/**
 * DeepSeek 续聊助手 —— 上游适配契约（阶段 0 核验后维护）。
 *
 * 当前值核验对象：deepseek-ai/chat-demo @ 5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c（v0.1.1）
 * 核验日期：2026-11-18
 *
 * - localStorageKeys：src/services/settings.ts 中 KEYS.apiKey = 'chat-demo:api-key'。
 *   上游把 API Key 明文存 localStorage；壳层用 Storage 垫片把它迁出到
 *   Android Keystore 加密存储（设计稿 6.2），页面只保留内存副本。
 * - importFileInputSelectors：src/components/settings/SettingsDialog.tsx 中
 *   ImportHistory 组件的隐藏 <input type="file">，accept 精确匹配。
 * - 导出：SettingsDialog.tsx ExportHistory 使用游离 <a download> + link.click()
 *   触发 blob 下载；WebView 无法原生落盘 blob，native-bridge.js 以
 *   HTMLAnchorElement.prototype.click 补丁接管（详见 docs/01_集成指南.md）。
 *
 * 升级上游 commit 时，必须重新核对以上三项并跑回归（docs/01_集成指南.md）。
 */
window.__ASSISTANT_CONFIG__ = {
  chatEntryUrl: 'https://appassets.androidplatform.net/assets/chat/index.html',
  importStreamUrl: 'https://appassets.androidplatform.net/__import_local__/current.zip',
  localStorageKeys: ['chat-demo:api-key'],
  importFileInputSelectors: [
    'input[type="file"][accept=".zip,application/zip,application/x-zip-compressed"]'
  ],
  /** 导出分块：256 KB / 帧，避免单条 WebMessage 过大 */
  exportChunkBytes: 262144,
  /** WebView 内导出上限 400 MB，超出提示改用桌面浏览器 */
  maxExportBytes: 419430400
};
