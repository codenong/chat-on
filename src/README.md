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
→ 菜单“导入 DeepSeek 聊天记录”选择网页端导出的 ZIP → 在勾选对话框选择要导入的对话
（或点「全部导入」）→ 追加导入完成后回到会话列表续聊（现有对话全部保留）。

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
│   ├── fileimport/ArchiveScanner.kt     # 官方导出包扫描（manifest + 会话索引）
│   ├── fileimport/HistoryIndex.kt       # 会话索引解析与过滤
│   ├── fileimport/FilteredArchiveBuilder.kt   # 按所选会话过滤重建导出包
│   ├── fileimport/CheckableLinearLayout.kt    # 会话勾选列表项容器
│   ├── fileimport/ImportController.kt   # 导入编排：扫描 → 勾选 → 过滤重建 → 追加导入
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

## 八、版本历史

- **0.2.4（versionCode 11）**：修复会话选择对话框勾选框不显示选中状态的问题。
  三层兜底：子 CheckBox 在每次绑定与点击时直接按勾选模型赋值（不再依赖
  CheckableLinearLayout 内部同步链）；CheckableLinearLayout.setChecked 移除
  早退判断，无条件同步（ListView 复用的 scrap view 可能带旧状态）；点击瞬间
  直接刷新本行视觉，不等 notifyDataSetChanged 重排。
- **0.2.3（versionCode 10）**：修复「勾选一个却全部导入」误导入事故。根因是对话框
  默认全选 + 点击反选：用户「点选一个」的直觉操作实际取消该会话，其余 N-1 个全部
  被导入。修复：默认不选（点谁选谁）；点击挂行自身监听，不再依赖 ListView 点击
  派发；「导入所选」按钮实时显示将导入数量（如「导入所选（2）」），未勾选时禁用；
  导入流 URL 附时间戳查询参数，杜绝 WebView 缓存返回上一次导入的旧包。
- **0.2.2（versionCode 9）**：修复 Material 对话框主题崩溃（ThemeEnforcement
  "requires Theme.AppCompat"）——0.2.1 的会话选择对话框误用 applicationContext，
  该 Context 在部分设备上不携带 manifest 应用主题。根治措施：ImportController 与
  BackupManager 移除多余的 applicationContext 参数，只保留 Activity Context（对话框、
  布局填充一律用 activity）；新增 `scripts/check_ui_context.sh` 审计全部
  MaterialAlertDialogBuilder / LayoutInflater.from 调用点并纳入打包前置检查。
- **0.2.1（versionCode 8）**：恢复 0.1.4 的「会话可选导入」——选 ZIP 后原生扫描官方
  导出包索引（manifest + chat_session_index.json），弹出勾选对话框（全选/清空/计数，
  「全部导入」整包直走），按所选会话过滤重建 ZIP 后走 0.2.0 的追加导入链路；
  兜底旧链路同样投递重建包。帮助页、README 同步更新。
- **0.2.0（versionCode 7）**：追加导入——页面内直接调用官方解析器与写库函数，
  跳过官方「清库再搬入」提交；现有对话保留，同 id 会话按导入侧覆盖。模块指纹失效
  （MODULE_NOT_FOUND）自动回退 0.1.x 官方 UI 链路。该版本重构时误移除了会话勾选，
  由 0.2.1 恢复。
- **0.1.4**：选择导入首版（解析索引 → 勾选对话 → 过滤重建 zip → 官方覆盖式导入）。
- **0.1.0**：首版实现（Key 安全存储、ZIP 投递官方导入器、备份导出落盘、引导与帮助页）。
