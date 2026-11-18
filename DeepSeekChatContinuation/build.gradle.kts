// DeepSeek 续聊助手 —— 根构建脚本
// 上游 chat-demo 以固定 commit 形式 vendored 到 app/src/main/assets/chat/，
// 本壳不修改聊天核心逻辑；升级上游必须经过回归测试（见 docs/01_集成指南.md）。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
