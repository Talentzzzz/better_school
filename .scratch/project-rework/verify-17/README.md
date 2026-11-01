# 票 17 冒烟走查（verify-17）

全栈已由 `backend/scripts/dev-stack.ps1 up` 起好（三服务 + 中间件 + 三库 + xxl 注册 + MQ/Nacos 清理）。
本目录是冒烟证据与脚本的唯一落点。

## 运行口径

| 项 | 值 |
| --- | --- |
| Python | `python`（3.13，标准库足够；不装任何包） |
| 单体 | http://localhost:8080（`better_school` 库） |
| marketing | http://localhost:8081（`bs_marketing` 库） |
| timeout-center | http://localhost:8083（`bs_timeout` 库） |
| 单体日志（抓短信验证码） | `backend/better-school/logs/dev-stack-bs.log` |
| marketing 日志 | `backend/marketing/logs/dev-stack-marketing.log` |
| center 日志 | `backend/timeout-center/logs/dev-stack-center.log` |
| 共享工具 | [`smoke_lib.py`](smoke_lib.py)（登录 / 查库 / 证据落盘全在里面） |
| 证据目录 | [`evidence/`](evidence/) |

跑法：

```powershell
cd C:\Users\a1368\Desktop\better_school
python .scratch\project-rework\verify-17\smoke_NN_xxx.py
```

## 手机号段（各脚本独占，避免互相踩）

| 脚本 | 号段 | 说明 |
| --- | --- | --- |
| `smoke_01_08_review.py` | `137000171xx` | 发帖/评论先审后发 + 后台审核 |
| `smoke_02_05_trade_wallet.py` | `137000172xx` | 商品上下架 + 钱包支付 |
| `smoke_03_04_errand_chat.py` | `137000173xx` | 跑腿单人单全流程（含超时回调）+ 1v1 私信 |
| `smoke_06_07_09_points_lottery.py` | `137000174xx` | 积分桶 + 抽奖全链路 + 通知落库 |

## 九项清单与判定口径

| # | 项 | 必须留下的证据 |
| --- | --- | --- |
| 1 | 发帖 / 评论先审后发（敏感词初筛 → 人工） | 命中敏感词的正文**不落库 / 直接拒**；干净正文落 `state=PENDING_REVIEW` 且 `content_review` 有 MANUAL 行；旁人列表看不到 |
| 2 | 商品上下架 | 上架→列表可见；下架→列表不可见且详情给不出在售态 |
| 3 | 跑腿单人单全流程（含超时回调） | 建单→支付→接单→取件→送达→确认的全状态轨迹（`errand_order_event` 逐行）+ 一条**过期未支付**订单被 timeout-center 扫到并回调改判（`bs_timeout` 侧 `timeout_task` + 单体侧状态变化） |
| 4 | 1v1 私信 | 建会话（恒两行 `room_member`）→ 发消息 → 对方拉到 → 已读推进 |
| 5 | 钱包支付 | 余额扣减 + `wallet_detail` 流水 + 重复支付幂等（同 `uk_order_type` 不重复扣） |
| 6 | 积分桶入账 + 消费扣桶 | `points_bucket` 入账（FIFO 桶）+ `points_detail` 流水；消费按桶递减 `remain`，跨桶时按到期顺序扣 |
| 7 | 抽奖全链路（次数 → 责任链 → 库存预扣 → 合并任务 → 发货） | 次数桶扣减 → `draw_record`+`draw_record_detail` → 库存预扣（`state=0` 明细）→ `inventoryMerge`（xxl，秒级）把预扣翻进 `issued_qty`/`waiting_qty` → 发货状态机（虚拟档直达已完成 / 实物档先 `waiting→issued` 再已发货）→ outbox 中奖事件被消费 |
| 8 | 后台审核 | 队列可拉、通过/驳回都改状态 + 落 `content_review` 裁决行 + 通知对方 |
| 9 | 通知（中奖 / 积分变动）落库 | `notification` 表按收件人有行（中奖通知 / 积分变动通知），内容字段非空 |

**判定纪律**：失败项必须给出「是编排问题（端口/配置/topic/group/xxl 注册）还是业务缺陷」的判定；
业务缺陷**不在本票改**，记进票 17 `## Answer` 的遗留清单（附另开票编号）。

## 编排面已知事项（写脚本时可直接用）

- `127.0.0.1:9090` 是 xxl-job-admin（`admin` / `123456`），`outboxScan` 等任务已在跑；抽奖/库存的合并任务 `inventoryMerge` 是 **1 秒**周期。
- RocketMQ 开发 broker 在跑（namesrv 9876），outbox 消息**会真的投递并被消费**——通知、计数落库可以靠真实 MQ 链路验证。
- ES 在跑（9200）；`sensitive_word` 种子由 `better-school/sql/schema.sql` 灌入（8 条）。
- 抽奖相关的活动 / 奖池 / 策略 / 库存**没有种子数据**，需要脚本自己插（可参照
  `backend/marketing/marketing-app/src/test/java/com/betterschool/marketing/integration/support/RaffleApiTestBase.java`
  的夹具写法，那是同一套表的权威用法）。
