# 01: 根杂物归档

**Spec:** `.scratch/service-restructure/spec.md` §5（仓库根杂物归置）

**What to build:** 仓库根目录清干净——截图/设计稿类杂物流转进 `docs/archive/`，过期 vite 日志删除。纯文件搬移，不动任何代码，随时可做、与后续各票并行安全。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 5 张散图归档到 `docs/archive/`（保留原文件名）：`55a4b0d413d2fb4ee8c844d69d906455.jpg`、`fcc256337cab8d96e8e6edcccf94ff87.jpg`、`示例.jpg`、`市集.png`、`ScreenShot_2026-09-11_022904_160.png`
- [x] `评论/`（3 张 png）整目录归档到 `docs/archive/评论/`
- [x] `实现方案3.md` 归档到 `docs/archive/实现方案3.md`
- [x] 删除 `front/vite-ticket01.log` 与根目录 `vite-ticket07.log`、`vite-ticket08.log`（后两者被 `.gitignore` 全局 `*.log` 覆盖，git status 看不到，但文件在盘上）
- [x] **不动**：`CLAUDE.md`、`CONTEXT.md`、`docs/` 其余内容、`.scratch/`、`front/`、`agent/`、`.claude/`
- [x] 收尾后仓库根除上述保留项与 `backend/` 外无散落文件；`git status` 无意外未跟踪产物

## 实现要点（票 01）

- 5 张散图 + `实现方案3.md` 为未跟踪文件，直接 `mv` 进新建的 `docs/archive/`；`评论/` 是已跟踪目录，走 `git mv` 保留历史。
- 三个 vite log 均被 `.gitignore` `*.log` 覆盖（含 `front/vite-ticket01.log`，`git ls-files` 证实未被跟踪），直接删除，无 git 操作。
- 纯文件搬移，未动任何代码；无测试/构建可跑。
- 已知不在本票范围：根目录 `.claude/` 与 `.scratch/` 下其他 effort 的未跟踪产物（`tokens.json`、`smoke04.json`、`e2e-assets/`）保持原样，票面「不动」清单已列明。
