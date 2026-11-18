#!/usr/bin/env bash
#
# 将官方 chat-demo 以固定 commit vendored 到 app/src/main/assets/chat/
# 用法：
#   scripts/fetch_chat_demo.sh                    # 使用脚本内置默认 commit（推荐）
#   CHAT_DEMO_COMMIT=<sha> scripts/fetch_chat_demo.sh
#   CHAT_DEMO_REPO=<url> CHAT_DEMO_COMMIT=<sha> scripts/fetch_chat_demo.sh
#
# 依赖：git、Node.js 22.12+、pnpm（corepack 可自动启用）。
# 升级 commit 后必须按 docs/01_集成指南.md 重新核对 upstream-config.js 的适配契约。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ASSET_DIR="${PROJECT_ROOT}/app/src/main/assets/chat"

# 阶段 0 锁定的 commit（2026-11-18 核验，v0.1.1）
DEFAULT_COMMIT="5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c"
REPO_URL="${CHAT_DEMO_REPO:-https://github.com/deepseek-ai/chat-demo}"
COMMIT="${CHAT_DEMO_COMMIT:-${DEFAULT_COMMIT}}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

echo "==> 克隆 ${REPO_URL} 并切换到 ${COMMIT}"
git clone "${REPO_URL}" "${WORK_DIR}/repo" >/dev/null 2>&1
git -C "${WORK_DIR}/repo" checkout --quiet "${COMMIT}"
RESOLVED="$(git -C "${WORK_DIR}/repo" rev-parse HEAD)"
echo "    实际 commit：${RESOLVED}"

echo "==> 安装依赖并构建（pnpm install / pnpm build）"
cd "${WORK_DIR}/repo"
if ! command -v pnpm >/dev/null 2>&1; then
  echo "    pnpm 未找到，尝试 corepack enable"
  corepack enable >/dev/null 2>&1 || true
fi
pnpm install --frozen-lockfile
pnpm run build

echo "==> 拷贝 dist/ 到 ${ASSET_DIR}"
mkdir -p "${ASSET_DIR}"
rm -rf "${ASSET_DIR}/index.html" "${ASSET_DIR}/static" "${ASSET_DIR}/LICENSE"
cp -r "${WORK_DIR}/repo/dist/." "${ASSET_DIR}/"
printf '%s' "${RESOLVED}" > "${ASSET_DIR}/COMMIT.txt"

echo "==> 完成。当前 vendored commit 已写入 app/src/main/assets/chat/COMMIT.txt"
echo "    下一步：核对 app/src/main/assets/inject/upstream-config.js 的适配契约，"
echo "    并按 docs/04_测试与验收.md 跑导入/导出回归。"
