# Issue tracker：本地 Markdown

本仓库的 issue 和 spec 以 Markdown 文件形式存放在 `.scratch/` 目录下。

## 约定

- 每个功能一个目录：`.scratch/<feature-slug>/`
- spec 是 `.scratch/<feature-slug>/spec.md`
- 实现 issue 一票一文件，路径 `.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号，绝不合并成单个 tickets 文件
- Triage 状态记录在每个 issue 文件顶部的 `Status:` 行（角色字符串见 `triage-labels.md`）
- 评论和讨论历史追加到文件底部的 `## Comments` 标题下

## 当 skill 说 "publish to the issue tracker"

在 `.scratch/<feature-slug>/` 下创建新文件（必要时创建目录）。

## 当 skill 说 "fetch the relevant ticket"

读取引用路径的文件。用户通常会直接给出路径或 issue 编号。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一个文件，每张票对应一个 **child** 文件。

- **Map**：`.scratch/<effort>/map.md`（Notes / Decisions-so-far / Fog 内容）。
- **Child ticket**：`.scratch/<effort>/issues/NN-<slug>.md`，从 `01` 开始编号，正文是问题。`Type:` 行记录票类型（`research`/`prototype`/`grilling`/`task`）；`Status:` 行记录 `claimed`/`resolved`。
- **Blocking**：顶部附近的 `Blocked by: NN, NN` 行。当它列出的每个文件都是 `resolved` 时，该票解除阻塞。
- **Frontier**：扫描 `.scratch/<effort>/issues/` 中开放、未阻塞、未被认领的文件；编号最小者优先。
- **Claim**：开工前先设 `Status: claimed` 并保存。
- **Resolve**：在 `## Answer` 标题下追加答案，设 `Status: resolved`，然后把上下文指针（要点 + 链接）追加到 `map.md` 的 Decisions-so-far。
