# 内嵌超时中心管理面（死信）

> 业务：超时中心（内嵌于单体，内嵌化票 06）；归属模块：`bs-trigger/http/timeout`。
> 业务注册/回调不经 HTTP——对业务全在进程内（原跨进程边与专用端口已随内嵌化删除）；
> 这套是**唯一的运维面**：回调连续失败 16 次进 `FAILED` 死信后的人工出口。

## 鉴权

- 需要登录（JWT 过滤链，未登录 401）；角色收口 **ADMIN**（USER 403）。

## 分页口径

- 每页上限 50、页码上限 1000，越界**夹住不报错**（下界归一到 1）。
  列表是跨 16 分表的广播归并（SQL 无分片键、故意不塞 hint），深翻页代价是 O(页码 × 页宽 × 16)；
  且与用户请求共享连接池——翻到上千页说明该按行处理而不是继续翻。

## GET /api/admin/timeout-tasks/dead-letters

死信列表（`updated_at` 倒序，最近进死信的在前；`id` 倒序兜底同秒撞车）。

- Auth: ADMIN
- Query:
  - page: number 选填，默认 1（≤1000）
  - pageSize: number 选填，默认 10（≤50）
- 成功: `data = { page, pageSize, total, list }`，list 行：`taskType / bizId / actionTime / nextFireTime / retryCount / createdAt / updatedAt`
  （无 `id`：按自然键动作，不暴露物理主键；`status` 恒为 FAILED 不占一列）

## POST /api/admin/timeout-tasks/dead-letters/resend

重发死信（单条也放进 `keys` 列表）：`FAILED → RETRYING`、`retry_count` 清零、`next_fire_time = now`（**不动 `action_time`**），
走同一个扫描 job 入口重投，下一个 10s 周期即被捞起——不新开重试通路。

- Auth: ADMIN
- Body:
  - keys: array 必填，元素 `{ taskType, bizId }`
- 成功: `data = { successList, failList }` 双清单。**failList 不是错误**：该键当前不是 FAILED
  （已被人处理过 / 键写错了），无副作用。

## POST /api/admin/timeout-tasks/dead-letters/cancel

手动取消死信：`FAILED → CANCELED` 终态。

- Auth: ADMIN
- Body:
  - taskType: string 必填
  - bizId: string 必填
- 成功: `data = true/false`。`false` = 该键当前不是 FAILED（或不存在），无副作用。

## 注意

- 重发与取消只打 `FAILED` 行（条件 UPDATE，并发安全）：两个管理员抢同一条只有一个赢，不需要加锁。
- 取消一个**正在重试**的任务不走这里（那是业务取消，打未终态行）——管理面只收死信。
- 营销侧（`bs_marketing`）本期没有管理面：零超时接入方，将来接入超时中心时按同款落（票 08 规格）。
