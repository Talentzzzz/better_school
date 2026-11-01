# 项 2 商品上下架 —— 冒烟结论

- **脚本**：`smoke_02_05_trade_wallet.py`（号段 `137000172xx`：seller `13700017201` / buyer `13700017202` / admin `13700017203`）
- **判定**：**PASS**（45 条断言全绿；连跑两次均 PASS，脚本可重复执行）
- **证据摘要**：`02-05-summary.json`（含逐条断言与 `state_trace`）、`02-05-sql-log.txt`（本项所有 SELECT 原文）

## 断言与证据

| # | 断言（全部 PASS） | 证据文件 |
| --- | --- | --- |
| 1 | 发布返回 `productId` 且落库；`state=PENDING_REVIEW`、`review_round=1`、`review_entered_at` 非空（先审后售） | `02-01-publish-response.json` |
| 2 | 待审期间公开列表（匿名）看不到 | `02-02-pending-anon-list.json` |
| 3 | 待审商品进入 ADMIN 审核队列（`GET /api/moderation/queue?targetType=PRODUCT`）；裁决后移出队列 | `02-03-review-queue-before.json` |
| 4 | `POST /api/admin/reviews/decide`（approve=true）→ `state=ON_SALE`；`content_review` 落 `PRODUCT/APPROVE/MANUAL/operator_id=5` 一行；公开列表可见（匿名 + 买家登录态） | `02-04-approve-and-onsale.json` |
| 5 | 上架后他人可看详情（业务码 200） | `02-05-onsale-detail-by-other.json` |
| 6 | 下架（`POST /api/trade/products/{id}/takedown`）→ `state=DRAFT`（汇入草稿箱）；公开列表不可见（匿名 + 买家登录态）；他人看详情业务码 `404`；卖家「我的发布」仍可见；**不可下单**（`POST /api/trade/orders?productId=` → `404 商品不存在或已售出`）；`product_order` 无新行 | `02-06-takedown.json` |
| 7 | 状态机闭环：草稿重提 → `PENDING_REVIEW` 且 `review_round=2` → 再次裁决通过 → `ON_SALE` 且重新公开可见 | `02-07-resubmit-reonsale.json` |

## `product` 表字段状态迁移（本次实测，product_id=8）

| 步骤 | `state` | `review_round` | `review_entered_at` | `expire_at` |
| --- | --- | --- | --- | --- |
| 发布（先审后售） | `PENDING_REVIEW` | 1 | 有值 | 有值（+30 天） |
| ADMIN 裁决通过 | `ON_SALE` | 1 | 保留 | 保留 |
| 下架 takedown | `DRAFT` | 1 | 保留 | 保留 |
| 草稿重提 submit | `PENDING_REVIEW` | **2**（+1） | **刷新** | **刷新** |
| 再次裁决通过 | `ON_SALE` | 2 | — | — |

`state_trace = PENDING_REVIEW → ON_SALE → DRAFT → PENDING_REVIEW → ON_SALE`。
（下单后另有 `LOCKED`、成交后 `SOLD` 两次迁移，见 `05-wallet-payment.md`。）

## 失败/偏差

**无业务缺陷。** 本项失败排查中出现的两处问题均为**编排问题（脚本侧），非业务缺陷**：

1. `smoke_lib.Harness.promote_admin()` 原先写 `WHERE mobile=...`，而 `bs_user` 的手机号列名是 `phone`
   → SQL 报 `Unknown column 'mobile'`。**属编排问题**，已由父代理在 `smoke_lib.py` 修正；
   本脚本改用修好的 `harness.promote_admin()`。
2. 本脚本自带的 `product_ids/queue_ids` 辅助函数原先按「完整响应 `{code,info,data}`」取值，
   而 `Harness.ok()` 已经剥掉外层只返回 `data` → 列表恒为空、队列断言假失败。
   **属编排问题（脚本取数形状写错）**，已修（现在两种形状都兼容）。

接口与库内行为本身全程自洽：无 500/999、无状态跳变异常。
