# 票 04 · 兜底扫描的频率与索引

Type: grilling
Status: resolved
Blocked by: —

## Question

票 01 定了「保留兜底扫描」，票 03 收口后仍是两张明细表（`physical_marketing_quota_detail` / `virtual_inventory_quota_detail`）。要定三件事：

1. **频率**：30s 还是 60s？判据是「消息丢失到被发现」的最长等待——它决定 `waiting_qty` 最坏晚多久、发货确认撞 4312 的窗口有多大。
2. **索引**：兜底扫的是全局 `state = 0`，用不上 `(inv_id, bucket_id, state, create_time)`（前缀是 `inv_id`）。要不要加 `(state, inv_id, bucket_id, create_time)`。
3. **唤醒方式**：兜底自己直接合并，还是给每个 `(inv, bucket)` 发一条唤醒消息？

分工先说清：消费者一次唤醒会循环合多格（上限 10），所以兜底**不负责积压**，只负责「消息整条丢了」——包括 outbox 进 DEAD、去重赢了但事务失败、以及晚提交落在已合完的秒里。

➡️ 倾向：

- **60s**（与 outbox 扫描同频；最坏延迟可接受，且不会让兜底成为热路径）。
- **加 `(state, inv_id, bucket_id, create_time)`**：全局扫 `state=0` 不带它就只能全索引扫。
- **直接调同一个域服务方法**（消费者调的那个 `mergeKey`），不发消息——发消息要多一次 outbox 写入和一个「延迟 0」的特例，而并发已经由 affected 判重扛住。

## Answer

三条都按推荐定：

1. **60s**。与 outbox 扫描同频；最坏口径是「丢一条消息 → 60s 后被发现」，期间该 key 的 `waiting_qty` 不涨、发货确认撞 4312 重投——重投本身能吸收。
2. **加 `(state, inv_id, bucket_id, create_time)`**（两张明细表各一个）。兜底是全局 `state = 0` 扫，现有索引前缀是 `inv_id`，用不上。
3. **兜底直接调域服务的 `mergeKey(segment, invId, bucketId)`**，不发消息——少一次 outbox 写入和「延迟 0」的特例；与消费者并发由 `affected` 判重扛住。

每轮按 `(segment, inv_id, bucket_id)` 去重、`LIMIT 200` 个 key，逐个 `mergeKey`。
