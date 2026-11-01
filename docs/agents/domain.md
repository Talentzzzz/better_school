# 领域文档

工程类 skill 在探索本代码库时应如何消费领域文档。

## 探索前先读这些

- 仓库根目录的 **`CONTEXT.md`**；或者
- 如果存在根目录的 **`CONTEXT-MAP.md`**：它指向每个上下文各自的 `CONTEXT.md`，读与主题相关的那些。
- **`docs/adr/`**：读与你要改动的区域相关的 ADR。多上下文仓库还要查 `src/<context>/docs/adr/` 里的上下文级决策。

如果这些文件不存在，**静默继续**。不要标记缺失，也不要主动建议创建。`/domain-modeling` skill（通过 `/grill-with-docs` 和 `/improve-codebase-architecture` 进入）会在术语或决策真正敲定时惰性创建它们。

## 文件结构

单上下文仓库（本仓库采用此布局）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `CONTEXT-MAP.md` 时）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← 上下文级决策
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## 使用词汇表的术语

当你的输出提到领域概念时（issue 标题、重构提案、假设、测试名），使用 `CONTEXT.md` 中定义的术语。不要漂移到词汇表明确避免的同义词。

如果你需要的概念还不在词汇表里，这是一个信号：要么你在发明项目不用的语言（重新考虑），要么存在真实缺口（记下来交给 `/domain-modeling`）。

## 标记 ADR 冲突

如果你的输出与现有 ADR 矛盾，显式指出而不是静默覆盖：

> _与 ADR-0007（event-sourced orders）矛盾，但值得重新讨论，因为……_
