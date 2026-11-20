# DeepSeek API 申请指南

**DeepSeek（深度求索）** 提供了高性价比的大模型服务。按照以下步骤，即可快速获取你的专属 API Key。

---

## 📋 申请步骤

### 1. 注册 / 登录
访问 [DeepSeek 开放平台 (platform.deepseek.com)](https://platform.deepseek.com/) 并使用手机号或邮箱登录账号。

> 💡 **提示**：新用户通常会有一定的免费体验额度，登录后可在控制台查看。

---

### 2. 进入 API Keys 页面
登录进入控制台后，在**左侧菜单栏**中找到并点击 **"API Keys"** 选项。

---

### 3. 创建 Key
1. 点击页面上的 **"创建 API Key"** 按钮。
2. 在弹出的对话框中输入一个便于识别的名称（例如：`VocoType`）。
3. 点击 **"创建"** 确认生成。

---

### 4. 复制并保存 Key
创建成功后，系统会立即显示生成的 API Key（格式通常以 `sk-` 开头）。

> ⚠️ **重要安全提示**：
> - 该 Key **只会完整显示这一次**，关闭窗口后将无法再次查看！
> - 请立即点击复制按钮，并将其妥善保存在安全的地方（如密码管理器或环境变量中），切勿泄露给他人或上传至公开代码仓库。

---

## 🚀 快速测试示例 (cURL)

获取 API Key 后，你可以通过以下命令测试连通性：

```bash
curl https://api.deepseek.com/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer 你的_API_KEY" \
  -d '{
        "model": "deepseek-chat",
        "messages": [
          {"role": "system", "content": "You are a helpful assistant."},
          {"role": "user", "content": "Hello!"}
        ],
        "stream": false
      }'
```
