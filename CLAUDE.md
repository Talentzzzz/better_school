## Java 编码规范

- 业务载体类型（`XxxEntity` / `XxxVO` / `XxxPO` / `XxxDTO` / `XxxMessage` / `XxxResult` / `XxxConfig` 等）的**局部变量与方法参数**名优先取「类型名首字母小写」：`RebateConfigEntity rebateConfigEntity`、`PostPublishRequestDTO postPublishRequestDTO`、`MqOutbox mqOutbox`。同一作用域内出现两个同类型变量、或目标名已被占用时，保留语义名并加前缀区分（如 `primary` / `fallback`），不强行同名。
- **成员字段除外**——字段保持业务裸名（`roomId`、`fromUserId`），record 组件同理；因为字段名联动 Lombok 访问器、MyBatis 属性映射与 JSON 序列化。JDK 类型（`String`、`List` 等）仍用语义名，不套用该规则。

## SQL 规范（mapper XML）

- 单表语句一律不写表别名，列名不加前缀：`FROM user_room WHERE user_id = ...`，不写 `FROM user_room ur WHERE ur.user_id = ...`；`<sql>` 列片段同理（片段不带前缀，由 `<include>` 拼进语句）。
- 别名只在真有多表时引入（JOIN，或子查询引用同一张表需要区分内外层）。本仓库当前 SQL 层零 JOIN，因此 mapper 里不应出现任何表别名。
- **列名不写 `AS` 别名迁就 Java 字段**：列名是什么，PO/VO 字段就叫什么（`product_order_id` → `productOrderId`），下划线转驼峰由 `map-underscore-to-camel-case` 统一完成。需要改名的是 Java 侧，不是 SQL 侧。
- 唯一可写 `AS` 的场景：投影的是表达式（`MAX(...)`、`COUNT(...)`、`CASE` 等）本身没有列名时才给它命名；若该列只为排序用、不随行带出，直接不投影、`ORDER BY` 引用表达式即可。

## Agent skills

### Issue tracker

Issue 以本地 Markdown 文件形式存放在本仓库的 `.scratch/<feature>/` 目录下（无 git 远程仓库）。见 `docs/agents/issue-tracker.md`。

### Triage 标签

默认五角色词汇：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### 领域文档

单上下文布局：仓库根目录一个 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
