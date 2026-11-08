## Java 编码规范

- Entity 类型（`XxxEntity`）的**局部变量与方法参数**名必须以 `Entity` 结尾（如 `MessageEntity messageEntity`）。**实体类的成员字段除外**——字段保持业务裸名（`roomId`、`fromUserId`），因为字段名联动 Lombok 访问器、MyBatis 属性映射与 JSON 序列化。

## Agent skills

### Issue tracker

Issue 以本地 Markdown 文件形式存放在本仓库的 `.scratch/<feature>/` 目录下（无 git 远程仓库）。见 `docs/agents/issue-tracker.md`。

### Triage 标签

默认五角色词汇：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### 领域文档

单上下文布局：仓库根目录一个 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
