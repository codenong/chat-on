# DeepSeek 续聊助手｜第一版实现

> 版本：实现稿 v1.0（2026-11-18）
> 对应设计文档：《01_分阶段路径规划》《02_第一版功能与详细设计》《03_用户导出_API_Key充值与导入指南》
> 一句话：帮助用户把 DeepSeek 网页端导出的聊天记录导入 Android App，并用自己的 API Key 继续对话。

## 一、这是什么

本仓库是《02_第一版功能与详细设计》的实现：

- **聊天核心**：官方 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo)，
  以固定 commit（`5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c`，v0.1.1）构建后随 APK 打包，
  不修改任何聊天逻辑；MIT 许可证随产物保留。
- **Android 壳**：Kotlin + WebView（`WebViewAssetLoader` 以可信 origin 加载本地页面），
  只补充系统级能力：API Key 安全存储、ZIP 导入文件流投递、备份导出落盘、首次引导与帮助页。
- **BYOK**：用户自带 DeepSeek API Key；第一版无任何自有服务器、无账号体系、无统计 SDK。

## 二、快速开始

环境要求：Android Studio（或 JDK 17 命令行）；聊天核心产物已 vendored，无需 Node 环境。

```sh
# 构建 debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到真机后：首次启动出现引导页 → 设置 API Key（自动调用 `GET /models` 验证后加密保存）
→ 菜单“导入 DeepSeek 聊天记录”选择网页端导出的 ZIP → 在官方确认框确认 → 回到会话列表续聊。

重新 vendored 上游（升级或从零）：

```sh
scripts/fetch_chat_demo.sh     # 需要 Node.js 22.12+ / pnpm
```

## 三、目录结构

```
app/src/main/
├── java/com/example/chatcontinuation/
│   ├── MainActivity.kt                  # 壳承载：组装、菜单、返回键、下载接管
│   ├── WebViewConfig.kt                 # 安全配置、AssetLoader、导航白名单
│   ├── bridge/AssistantBridgeListener.kt# 受限桥接（origin-scoped WebMessageListener）
│   ├── bridge/DocumentStartScript.kt    # document-start 注入脚本组装（含启动期 Key）
│   ├── key/SecureKeyStore.kt            # Android Keystore AES-256-GCM 加密存储
│   ├── key/KeyVerifier.kt               # GET /models 验证（与上游行为一致）
│   ├── fileimport/FilePicker.kt         # SAF 系统文件选择器
│   ├── fileimport/ImportStreamPathHandler.kt  # 导入 ZIP 流式通道（无 Base64 桥接）
│   ├── fileimport/ImportController.kt   # 导入编排与覆盖确认
│   ├── backup/BackupManager.kt          # 导出备份分块落盘 + 备份说明
│   ├── ui/KeySetupDialog.kt             # Key 设置（默认隐藏、验证后保存）
│   ├── ui/OnboardingController.kt       # 首次启动引导
│   ├── ui/ShellUi.kt                    # 组件-Activity 解耦契约
│   └── util/LogRedactor.kt              # 日志脱敏（sk- 前缀兜底）
├── assets/
│   ├── chat/                            # 固定 commit 的官方构建产物 + COMMIT.txt
│   ├── inject/upstream-config.js        # 适配契约（三项，升级必核对）
│   ├── inject/native-bridge.js          # Storage 垫片 / 导入投递 / 导出接管
│   └── help/                            # 内置帮助页：导出 / Key / 导入 / 隐私
└── res/                                 # 文案（strings.xml 按设计稿第八节组织）、布局、图标、备份规则
docs/
├── 00_设计对应与交付范围.md             # 设计稿逐条 → 实现位置映射
├── 01_集成指南.md                       # vendoring、适配契约、桥接协议、排障
├── 02_安全检查清单.md                   # 第七节逐条状态 + 发布前人工项 + 已知限制
├── 03_隐私说明草稿.md                   # 用户可见隐私文档底稿
└── 04_测试与验收.md                     # 测试用例与 MVP 判定
scripts/fetch_chat_demo.sh               # 固定 commit vendoring 脚本
```

## 四、关键设计决策（与设计稿的对应）

1. **Key 迁出明文 localStorage（6.2/七）**：上游把 Key 存在 `localStorage['chat-demo:api-key']`。
   壳层在 document-start 注入 Storage 垫片：读取走内存副本（值来自 Keystore 加密存储），
   写入转发原生并删除明文条目；启动时由原生注入当前 Key，避免异步竞态。
   真实 localStorage 中始终没有明文 Key。
2. **导入复用官方导入器（6.4）**：SAF 选中的 ZIP 经 `ImportStreamPathHandler` 流式挂到
   可信 origin 下，页面 `fetch` 后构造 `File` 赋给上游隐藏的 file input 并派发 `change`，
   之后的覆盖确认、格式校验、分支树/时间戳/附件处理、结果提示全部由官方导入器完成。
   壳层不做任何 ZIP 解析。
3. **导出落盘补齐 WebView 能力（6.1 BackupManager）**：上游导出为 blob 下载，WebView 无法
   原生落盘；注入脚本拦截导出点击，256 KB 分块经受限桥接回传，先写缓存临时文件，
   用户选择保存位置后一次性拷贝；备份不含 API Key。
4. **受限桥接（7.4/7.5）**：`WebViewCompat.addWebMessageListener` + origin allowlist +
   主框架校验；不使用宽泛的 `addJavascriptInterface`；错误页以 null origin 加载。

## 五、构建与发布前的注意事项

- `applicationId` 目前为 `com.example.chatcontinuation`，发布前改名并做商标/品牌混淆审查；
  图标未使用 DeepSeek 品牌元素。
- release 构建已开启混淆与资源收缩；回归桥接与导入全链路后再发布。
- 发布前人工项（签名、隐私政策、合规评估）见 `docs/02_安全检查清单.md` 第五节。

## 六、已知限制

见 `docs/02_安全检查清单.md` 第六节（Key 在页面内存中必然可见、导入中取消依赖上游、
导出 400 MB 上限、备份恢复依赖上游导入器兼容性）。真机回归尚未执行，
执行清单见 `docs/04_测试与验收.md`。

## 七、许可

壳层代码：MIT（见 `LICENSE`）。`app/src/main/assets/chat/` 内含官方 chat-demo 构建产物，
遵循其自带 MIT License，分发时一并保留。第三方依赖遵循各自许可证。
