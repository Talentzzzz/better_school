# 票 03 · 虚拟段（virtual_inventory*）怎么办

Type: grilling
Status: resolved
Blocked by: —

## Question

用户拍板：**虚拟的也改成配额**。本票确认表的收口方式与它带来的连锁改动。

（原票面三选一 A/B/C 已不适用。）

## Answer

**两套 quota 并存，只删出入库那对表。**

| 现在 | 之后 |
|---|---|
| `physical_inventory` / `physical_inventory_detail` | 删除 |
| `physical_marketing_quota` / `_bucket` / `_detail` | **保留**（实物：`surplus → waiting → issued`） |
| `virtual_inventory` | → `virtual_inventory_quota`（虚拟：`surplus → issued`） |
| `virtual_inventory_bucket` | → `virtual_inventory_quota_bucket` |
| `virtual_inventory_detail` | → `virtual_inventory_quota_detail` |

- 「所有库存都是 quota」= 两段都用同一套 quota 模型（Redis 桶 + 明细 + 合并），不是同一张表。
- 两段共用一套触发：`SETNX` + 同事务落 detail + outbox，消息带 `segment` + `invId` + `bucketId`；`inventoryMerge` 这个 1 秒 job 删除，兜底是 60 秒任务。
- 明细：两套都加 `biz_type` / `biz_id`；实物另加 `type`（`1` 预扣 / `2` 兑换确认）。
- `preDeductWithFallback` 退化成「主档扣不出 → 换个 inv_id 再扣」。
- 波及：`PhysicalQuotaIntegrationTest` 去掉 `physical_inventory` 夹具；`VirtualInventoryIntegrationTest` 改名并补 biz 断言；`docs/db/` 两页更新；`CONTEXT.md` 的「预扣」「奖品类型」跟着改；`inventoryMerge` 从 xxl-admin 摘掉。
