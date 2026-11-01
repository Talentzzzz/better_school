# 07: 测试收口（两段参数化）

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §10（受影响的测试，第一行与两张孪生测试合并）

**What to build:** 把两个靠复制粘贴保持同步的集成测试类（虚拟权益 / 实物配额）合成**一个按 `segment`
参数化跑两遍**的类：同一套预扣 / 合并 / 幂等 / 限量耗尽 / 兜底 / 对账用例，两段各跑一遍。
两段已经同构，用例也该同构；从这一票开始，08 / 09 / 10 / 11 的新用例只往这一个类里加。

**Blocked by:** 06

**Status:** ready-for-agent

**预算：≈100k / 200k。** 纯测试重构，但**覆盖不许缩水**：先在实现要点里列一张
「旧用例 → 新用例」对照表，再动代码。若参数化夹具让某个实物专属用例（同步确认）难写，允许在参数化类里
用 `@Test` + `assumeTrue(segment == QUOTA)` 保留它，不要删。

## 验收

- [ ] 一个 `InventoryQuotaIntegrationTest`：`@ParameterizedTest` + `@EnumSource(InventorySegmentVO.class)`
      （或等价方式）把两段各跑一遍
- [ ] 覆盖不缩水，至少包含：预扣（成功 / 未预热 / 余量不足）、合并后 `state=0 → 1` 与台账变化、
      预扣幂等回放、限量耗尽（`state = exhausted`）、兜底（主档不足改扣兜底池，配额段零写入）、对账恒等式
- [ ] 实物专属用例（同步确认 `waiting → issued`）保留，并标注 11 会改成异步断言
- [ ] `RaffleApiTestBase` 提供按 `segment` 的夹具 helper（配额行 / 桶行 / 虚拟行），不再让测试各写一套 SQL
- [ ] `InventoryFaultIntegrationTest` 的夹具同样走 helper（服务名在 06 已换）
- [ ] 全量集成测试绿

## 实现要点

（落地时填：旧用例到新用例的映射表、参数化注入方式、两段夹具差异、被 `assumeTrue` 跳过的用例）

## 注意

- **先列表、后动手**：这张表是「没有漏测」的唯一证据；不要为了参数化好看删掉实物专属断言。
- 测试仍然直连常驻栈（不建容器）；本票不改 `application-test.yml`。
- 08 起合并语义会变（`mergeOnce` → `backstopOnce`/`mergeKey`）；本票只做**等价重排**，
  不要提前改断言去适配还没落地的行为。
