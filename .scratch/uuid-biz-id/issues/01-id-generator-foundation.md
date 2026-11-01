# 01: IdGenerator 基建（两库 types 模块）

**What to build:** 两个后端的公共 types 模块各有一个统一的业务 ID 生成入口：接口 + UUID 默认实现（36 位连字符规范形字符串）。此票是扩展期基建，无任何调用方切换——系统行为零变化，这是"把改动变容易"的第一步。UUID 是模拟雪花的默认实现，将来换真雪花只换实现不动调用方。

**Blocked by:** None (can start immediately)

**Status:** resolved (70d503c)

- [x] better-school 与 marketing 的 types 模块各有一处统一 IdGenerator（接口语义一致，是否共享代码按两库既有依赖关系定）
- [x] 默认实现产出可被 `UUID.fromString` 解析的 36 位小写规范形字符串
- [x] 有唯一性/格式的轻量单测
- [x] 全量测试保持全绿（本票不改任何现有行为）
