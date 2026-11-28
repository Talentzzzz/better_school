## Java 编码规范

- 业务载体类型（`XxxEntity` / `XxxVO` / `XxxPO` / `XxxDTO` / `XxxMessage` / `XxxResult` / `XxxConfig` 等）的**局部变量与方法参数**取「类型名首字母小写」：`MqOutbox mqOutbox`、`PostPublishRequestDTO postPublishRequestDTO`、`RebateConfigEntity rebateConfigEntity`。同作用域重名时保留语义名加 `primary` / `fallback` 前缀区分。
- **成员字段与 record 组件不套用**，保持业务裸名（`roomId`、`fromUserId`）——字段名联动 Lombok 访问器、MyBatis 属性映射与 JSON 序列化。JDK 类型（`String`、`List`）用语义名。

## SQL 规范（mapper XML）

- 单表语句不写表别名、列名不加前缀（`<sql>` 列片段同理），`FROM user_room WHERE user_id = ...`。别名只在真有多表时引入（JOIN，或子查询引用同一张表区分内外层）；本仓库零 JOIN，因此 mapper 里不应出现表别名。
- **列名不写 `AS` 迁就 Java 字段**：`product_order_id` → `productOrderId` 由 `map-underscore-to-camel-case` 完成，要改名的是 Java 侧。`AS` 只给本身没有列名的表达式（`MAX(...)`、`COUNT(...)`、`CASE`）用；仅排序用的表达式不投影，`ORDER BY` 直接引用。

## Agent skills

### Issue tracker

Issue 以本地 Markdown 文件形式存放在本仓库的 `.scratch/<feature>/` 目录下（无 git 远程仓库）。见 `docs/agents/issue-tracker.md`。

### Triage 标签

默认五角色词汇：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### 领域文档

单上下文布局：仓库根目录一个 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

### 后端分层

DDD 分包、模块依赖方向、仓储与事务边界见 `backend/CLAUDE.md`。
