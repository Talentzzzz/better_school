# 票 01 · 删除 gatekeeper

Type: task
Status: resolved
Blocked by: （无）

## Question

gatekeeper 整体移除：删仓库根 `gatekeeper/` 目录（6 个 Maven 模块，约 163 文件 1.9 万行，尚未接入 better-school，无任何 pom/代码引用）；根 `CONTEXT.md` 的「限流器（gatekeeper）」段落及其指针一并清掉；grep 全仓（docs、backend、front、.scratch 的 map/spec 引用可留历史不动，但活文档如 CLAUDE.md/CONTEXT.md/docs/adr 必须清）确认无残留引用。限流能力不做替代——产品决策为不做限流。

## Answer

已删除，限流能力无替代（产品决策）：

- `gatekeeper/` 整目录删除（6 个 Maven 模块 + measure；`git rm` 清掉 200 个跟踪文件，未跟踪的 `target/`、`measure/build|results` 构建产物一并 `rm -rf`）。
- 根 `CONTEXT.md` 末尾「### 限流器（gatekeeper）」整段及指针删除，词条表收在「信用分」止。
- `docs/adr/0006-gatekeeper-self-built-center.md`、`docs/adr/0007-gatekeeper-layering-and-protocol-module.md` 删除（map out-of-scope 明确不留档）。
- `backend/CLAUDE.md` §1 适用范围段去掉 gatekeeper 分包说明，只留「本文件管 backend/ 下这四个工程」。
- `.gitignore` / `.zcodeignore` 的 gatekeeper target 忽略行（含注释）删除。
- 验证：`grep -rniE "gatekeeper"` 全仓（排除 `.git`/`.scratch`/`node_modules`）零命中；`.scratch/` 下 gatekeeper 历史 spec/issues/map 按票面留档不动。backend/front/agent 无任何 pom/代码引用，无需构建验证。
