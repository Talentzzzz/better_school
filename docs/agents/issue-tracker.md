# Issue tracker：本地 Markdown

本仓库无 git 远程，issue 与 spec 都是仓库内的 Markdown，落在 `.scratch/`。

## 约定

- 一个功能一个目录 `.scratch/<feature-slug>/`，spec 是其下的 `spec.md`。
- 实现票一票一文件：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 编号，绝不合并成单个 tickets 文件。
- 票顶部记 `Status:`（取值见 [`triage-labels.md`](triage-labels.md)）；讨论历史追加到文件底部 `## Comments`。

## 当 skill 说……

- **"publish to the issue tracker"** → 在 `.scratch/<feature-slug>/` 下新建文件（必要时建目录）。
- **"fetch the relevant ticket"** → 读被引用路径的那个文件（用户通常直接给路径或编号）。

## Wayfinding（供 `/wayfinder`）

- **Map**：`.scratch/<effort>/map.md`，内容为 Notes / Decisions-so-far / Fog。
- **Child ticket**：`.scratch/<effort>/issues/NN-<slug>.md`，正文即问题；`Type:` 记 `research`/`prototype`/`grilling`/`task`，`Status:` 记 `claimed`/`resolved`。
- **Blocking**：`Blocked by: NN, NN` 列出的票全部 resolved，该票即解阻塞。
- **Frontier**：扫 `.scratch/<effort>/issues/` 里开放、未阻塞、未认领的票，编号最小者先做。
- **Claim / Resolve**：开工前设 `Status: claimed`；完成后在 `## Answer` 下写答案、设 `Status: resolved`，再把要点与链接追加到 `map.md` 的 Decisions-so-far。
