Type: task
Status: open
Blocked by: 01, 02, 03, 04, 05, 06, 07, 08

# 09 文档对齐 + GLOSSARY 词条沉淀

## 问题

spec User Stories 21 + Implementation Decisions「文档对齐 / 术语沉淀」：API 文档「支付（冻结悬赏），成功事务内转已发布」的宣称随本次实现成真；本规格引入的口径需落 GLOSSARY.md。

## 交付

1. 核对跑腿 API 文档：支付宣称与实际实现一致；核对商品侧文档口径（支付 / 取消的同事务描述）。
2. `GLOSSARY.md` 新增词条：**组合事务端口 / 孤儿预扣收敛 / 资金收口**，措辞风格沿用「购买 / 购买单 / 死信 / 对账」既有词条。
3. 若涉及决策性口径，按需在 `docs/adr/` 补一条对 ADR-0014 的推广说明或在其上追加 amendment（轻量，不重写 ADR-0014）。

## 验收

- 文档不再漂移；GLOSSARY 词条风格一致；不引入与 ADR-0014 矛盾的表述。
