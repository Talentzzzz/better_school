Status: resolved
Type: grilling
Blocked by: 05

# 06: 营销域接口口径（三服务对外 HTTP + 活动页聚合读归属）

## Question

定死前端要调的接口、字段与失败语义（表结构见票 05；**链路怎么连**归票 04，本票只管接口长什么样）。

**① 活动页的聚合读由谁提供（先答这个，它决定后面几问的形状）**：活动页要「我的邀请码 / 战绩 / 抽奖次数 / 积分余额 / 奖品目录 / 兑换定价」，横跨三个服务。三案：
- (a) **前端并发调三家自己拼**——简单、无不对称，代价是请求数多、页面要自己兜部分失败；
- (b) 某个服务对外提供一个聚合接口——BFF 味道，引入服务间不对称；
- (c) 抽奖服务当活动页的主服务（它拥有次数与奖品，是活动的主体）。

➡️ 助手推荐 **(a)**，但最终要看票 02 原型翻页后活动页到底切成几个区块——区块少就 (a)，区块多且强耦合再考虑 (b)。

**② 接口清单**（路径 / 方法 / 鉴权 / 关键参数 / 成功与失败要点），**每个强交互动作都要写清失败态的文案**：
- **拉新**：我的邀请码、我的战绩（已拉新人数 / 拉到的名单）、**填码成团**（失败态：码不存在 / 活动未开始 / 活动已结束 / 你是活动前注册的老用户 / 你已被邀请过 / 不能邀请自己）
- **抽奖**：活动信息与奖品目录（免登录？）、我的抽奖次数、**单抽**（失败态：次数不足 / 奖品已抽完）、我的中奖记录、我的奖品（含**领取表单提交**：收件人 / 手机号 / 地址的校验口径）
- **积分**：我的余额、我的流水、**兑换抽奖次数**、**兑换奖品**、兑换定价表（前端要展示「N 积分 = 1 次」）

**③ 分页与排序**：中奖记录 / 积分流水 / 我的奖品各自的默认排序（时间倒序？）与分页参数口径。

**④ 免登录白名单**：活动信息 / 奖品目录是否免登录可见（决定三个服务 `SecurityConfig` 的白名单）；填码与抽奖必须登录；访客点「活动」入口进活动页看到什么。

**⑤ 文档**：`backend/docs/db/` 的分服务表文档、`backend/docs/api/` 的分服务接口文档（照 `backend/CLAUDE.md` §9 的「像代码一样短」口径 + 关键流程图），以及 `backend/docs/README.md` / `docs/README.md` 的索引更新。

**⑥ 与票 07 的边界**：市集消息页 / 聚合订单页的接口归票 07；本票只出现在活动页与营销域自身的数据面。

## Answer

**2026-09-14 收口**：一轮 grilling（Q1–Q10）**全部采纳助手推荐**（用户回「都按你推荐的」）。开工前做了事实自核，**修正票面四处前提**（决策依据第 1–4 条），其中第 4 条（失败分支）决定了整份清单的形状。

### 1. 活动页的聚合读：前端并发调三家（Q1，票面 ①）

- 活动页 7 个区块归属逐块干净：banner / 抽奖主卡 / 奖品目录 / 底部 → **抽奖**，邀请码卡 / 填码卡 → **拉新**，积分卡 → **积分**。**无任何区块需要跨服务拼装**。
- ⇒ **方案 (a)**：前端并发调三家、各区块渲染各自的加载与失败态。页面约 6 个并发 GET（`/referral/invite-code`、`/referral/invitees?page=1&pageSize=8`、`/lottery/activity`、`/lottery/draw-chances`、`/lottery/prizes`、`/points/balance`）。
- **不做 BFF**（不让抽奖服务聚合别家）：省下的请求数不抵「抽奖要认识积分 / 拉新的接口、且被它们的故障拖慢」的代价，且引入服务间不对称。

### 2. 接口清单（Q2/Q3/Q4/Q5/Q6/Q9）

**拉新 `bs-referral` → `/api/referral`**

| 路径 | 方法 | 鉴权 | 关键参数 | 成功 | 失败要点 |
| --- | --- | --- | --- | --- | --- |
| `/api/referral/invite-code` | GET | 登录 | — | `{ code }`（8 位、懒生成） | — |
| `/api/referral/invitees` | GET | 登录 | `page` / `pageSize` | `PageResult<{ userId, nickname, avatar, groupedAt }>`；**`total` 即「已拉新 N 人」**，`list` 前 8 条即头像并排 | — |
| `/api/referral/groups` | POST | 登录 | body `{ code }`（前端转大写、后端 `@NotBlank @Size(max=16)`，查不到即 `CODE_NOT_FOUND`） | `{ groupId }` | `CODE_NOT_FOUND` / `ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `OLD_USER` / `ALREADY_INVITED` / `CANNOT_INVITE_SELF` |

- 邀请码卡 = 2 个请求（`invite-code` 拿码 + `invitees?page=1&pageSize=8` 拿人数与头像），不合并成一个「大杂烩」接口。
- `invite-code` 是 **GET 但会写库**（首查懒生成）：幂等由 `uk_user(user_id)` 兜，重复调用拿到同一码，可安全重放。

**抽奖 `bs-lottery` → `/api/lottery`**

| 路径 | 方法 | 鉴权 | 关键参数 | 成功 | 失败要点 |
| --- | --- | --- | --- | --- | --- |
| `/api/lottery/activity` | GET | **免登录** | — | `{ name, slogan, startAt, endAt, status, dailyLimit, drawCountPrice, drawCountValidDays }`，`status ∈ NOT_STARTED·ONGOING·ENDED` | — |
| `/api/lottery/prizes` | GET | **免登录** | — | `[{ prizeId, tier, name, image, type, pointsAmount, redeemPoints, remaining, total, unlimited }]` | — |
| `/api/lottery/draw-chances` | GET | 登录 | — | `{ available, todayDrawn }`（`available` = 未过期桶 `SUM(remain)`） | — |
| `/api/lottery/draw` | POST | 登录 | body `{ token }`（客户端 uuid，见 §4） | `{ recordId, prizeType, prizeId, tier, name, image, pointsAmount }`；**谢谢参与也走成功**（`prizeType = THANKS`） | `ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `NO_CHANCE` / `DAILY_LIMIT_REACHED` |
| `/api/lottery/records` | GET | 登录 | `page` / `pageSize` | `PageResult<{ recordId, prizeId, tier, name, image, prizeType, pointsAmount, createdAt }>`；**过滤 `prize_type = THANKS`** | — |
| `/api/lottery/my-prizes` | GET | 登录 | `status`（`PENDING`·`RECEIVED`·`CREDITED`）/ `page` / `pageSize` | `PageResult<{ id, prizeId, tier, name, image, prizeType, status, createdAt, claimedAt }>` | — |
| `/api/lottery/my-prizes/{id}/claim` | POST | 登录 | body `{ receiver, mobile, address }` | `{ id, status }` | `PRIZE_NOT_CLAIMABLE`（非本人 / 不存在 / 状态非 `PENDING`） |
| `/api/lottery/exchange/draw-count` | POST | 登录 | body 空（一次兑 1 次） | `{ redeemOrderId, drawCount }` | `ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `INSUFFICIENT_POINTS` |
| `/api/lottery/exchange/prize` | POST | 登录 | body `{ prizeId }` | `{ redeemOrderId }` | `PRIZE_NOT_REDEEMABLE`（`redeem_points` 为 NULL）/ `INSUFFICIENT_POINTS` / `OUT_OF_STOCK` / `ACTIVITY_ENDED` |
| `/api/lottery/buckets/open` | POST | **ADMIN** | body `{ prizeId, percent?, amount? }`（默认拨 80） | `{ bucketId, remaining }` | `PRIZE_NOT_FOUND` / `PARAM_ERROR` |

- **领取表单校验口径**（`@Valid` + jakarta，中文 message）：收件人 `@NotBlank @Size(max=30)`；手机号 `@NotBlank @Pattern(regexp="^1\\d{10}$")`；收货地址 `@NotBlank @Size(max=200)`。
- **开桶接口**（Q9）：**ADMIN 鉴权**（照 `NotificationController` 的控制器内 role 校验）、**不进白名单**；无 admin UI ⇒ 用 curl / HTTP client 打；返回新桶或「已有开启桶则按该行 `remaining` 重 SET」后的 `bucketId`。
- **库存对外字段**（`/api/lottery/prizes` 的 `remaining` / `total`）：`remaining = sq`（剩余可中奖）、`total = lottery_prize.total`（**投放总量、建品时定、恒不变**）；**不暴露 `lq` / `oq`**（`lq` 是「拨给 redis 多少」的实现细节）。不限量档（POINTS / THANKS）给 `unlimited: true`、两字段 null。
  - ⚠️ **`total` 必须是独立列，不能拿 `sq + oq` 顶替**：`sq + oq` 只在「开桶 / 合并提交 / DB 直扣」三种动作下守恒，而**实物领取会 `oq -= 1`**（票 05 §5⑥）⇒ 每领取一件 `sq + oq` 就掉 1，当分母会让「剩 3/5」变成「剩 3/4」。故由本票回填票 05 给 `lottery_prize` 补 `total` 列（见「决策依据」第 6 条与「转下游」）。

**积分 `bs-points` → `/api/points`** —— **只有两个只读接口，零写接口**（Q4，见 §5）

| 路径 | 方法 | 鉴权 | 关键参数 | 成功 |
| --- | --- | --- | --- | --- |
| `/api/points/balance` | GET | 登录 | — | `{ balance }` |
| `/api/points/transactions` | GET | 登录 | `page` / `pageSize` | `PageResult<{ id, direction, amount, reasonType, refId, createdAt, expireAt }>`，`direction ∈ IN·OUT`（`IN` = `points_lot` 入账行、`OUT` = `points_consume` 消费行，应用层按时间归并两表） |

### 3. 失败语义：业务 code 分支 + `info` 中文文案（Q2）

- **成功码一律 `"200"`**（前端 `unwrap` 的 `code !== '200'` 立刻可用，`Response.ok` 已是它）。
- **业务失败 = 独立 code（`4xxx` 段）+ 中文 `info`**：三个服务**各自**的 `-types/enums/ResponseCode` 里加自己的业务码（无全局注册表），case/domain 抛 `AppException(code, info)`，`GlobalExceptionHandler` 落成 `Response{code, info}`。
- 前端在 `api/marketing.ts` 层**按 code 分支**——命中 `NO_CHANCE` → 弹票 02 已定的「次数不足」专用弹窗，命中填码五码 → 展开对应文案；**未识别的 code 落回默认 toast `info`**，保住兜底。
- **唯一要改的前端公共件**：`front/src/api/http.ts` 的 `unwrap` 增加「调用方自己处理失败」的开关——否则业务 code 已经被专用弹窗处理，默认 toast 还会抢先弹一次 `info`。

**失败态文案**（票面 ② 要求「每个强交互动作都要写清失败态的文案」；文案由后端 `info` 逐条固定为唯一来源，前端只对票 02 定了专用 UI 的两处按 code 分支）：

| 动作 | code | `info` 文案 | 前端处理 |
| --- | --- | --- | --- |
| 填码 | `CODE_NOT_FOUND` | 邀请码不存在 | toast |
| 填码 | `ACTIVITY_NOT_STARTED` | 活动还没开始 | toast |
| 填码 | `ACTIVITY_ENDED` | 活动已结束 | toast |
| 填码 | `OLD_USER` | 你是活动开始前注册的老用户，不能参与 | toast（票 02 填码卡「失败时会怎么提示」第 4 条） |
| 填码 | `ALREADY_INVITED` | 你已经被邀请过了 | toast（第 5 条） |
| 填码 | `CANNOT_INVITE_SELF` | 不能填自己的邀请码 | toast（第 6 条） |
| 抽奖 | `NO_CHANCE` | 抽奖次数不足 | **专用弹窗**（票 02 表格第 3 行：去邀请好友 / 用积分兑换次数） |
| 抽奖 | `DAILY_LIMIT_REACHED` | 今天的抽奖次数已用完 | toast |
| 抽奖 | `ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` | 活动还没开始 / 活动已结束 | toast |
| 兑换 | `INSUFFICIENT_POINTS` | 积分不足 | toast（+ 引导去抽奖攒分） |
| 兑换 | `PRIZE_NOT_REDEEMABLE` | 该奖品不可兑换 | toast |
| 兑换 | `OUT_OF_STOCK` | 该奖品已兑完 | toast |
| 领取 | `PRIZE_NOT_CLAIMABLE` | 该奖品不可领取 | toast + 刷新列表 |
| 开桶 | `PRIZE_NOT_FOUND` / `PARAM_ERROR` | 奖品不存在 / 参数不合法 | 运营侧、无 UI |

- **不采纳**：不把 reason 塞进 `data`（多一层解包、还要动 `Response` 泛型）；不放弃分支（那会退化成「所有失败一句 toast」，票 02 已定的两处专用交互作废）。

### 4. 抽奖 POST 的幂等：客户端 token（Q3）

- `POST /api/lottery/draw` body 带 `{ token }`（前端生成的 uuid，同一意图重试用同一个）。
- `lottery_draw_record` 加 **`client_token VARCHAR(64)`** + **`uk_user_token(user_id, client_token)`**；撞键 ⇒ **回放**已存那条的结果（不重复抽、不重复扣次数）。刷新页面重发同 token 也能拿回刚刚那次结果。
- **⚠️ 这是对已 resolved 的 [票 05](05-marketing-data-model.md) `lottery_draw_record` 补一列 + 一唯一键**，票 05 §2⑤ 已留注记（见其「转下游」）。
- 理由：这是全仓「幂等 + 重试」铁律（`backend/CLAUDE.md` §1）在**唯一一个失败会让用户真亏一次抽奖**的写接口上的落地；成本 = 1 列 + 1 唯一键 + 一个「撞键回放」分支。

### 5. 兑换接口归属：挂抽奖，积分服务零写接口（Q4）

- **前端打抽奖**：`POST /api/lottery/exchange/draw-count`、`POST /api/lottery/exchange/prize`；抽奖服务**同步 Dubbo 调积分**扣分（`IPointsExchangeService`，票 04 §3）。
- **兑换定价归抽奖**（票 01 §5）：次数价 = `lottery_activity.draw_count_price`（随 `GET /api/lottery/activity` 返回），奖品价 = `lottery_prize.redeem_points`（随 `GET /api/lottery/prizes` 返回）。**不单独做定价接口**。
- ⇒ **积分服务对外只有 §2 的两个只读接口**，`IPointsExchangeService` 是它对内的 Dubbo 口、不是 HTTP 口。
- 修正票面 ② 把「兑换抽奖次数 / 兑换奖品 / 兑换定价表」列在积分下的说法（与票 01 §2·§5、票 04 §3 的既定边冲突）。

### 6. 分页与排序（Q7，票面 ③）

- 统一 `page` / `pageSize`（默认 `1` / `10`、上限 `50`），返回 `PageResult{page, pageSize, total, list}` —— 照 `/api/wallet/transactions`（`WalletController.java:56-67`）的现成形状。
- 排序**一律 `created_at DESC`**：我的中奖记录 / 我的奖品 / 积分流水 / 拉新名单。我的奖品也用 `created_at`（行上主时间「中奖于 …」就是它；`claimed_at` 另占一列展示）。
- **不分页**：活动信息（单行）、奖品目录（3~5 档）。

### 7. 免登录白名单与访客态（Q5，票面 ④）

- **白名单只两条**：`GET /api/lottery/activity`、`GET /api/lottery/prizes`（各服务 `SecurityConfig` 照票 04 §6 的机制写）。其余全部需登录；开桶接口另有 ADMIN 校验。
- `/marketing` 路由**不加 `requiresAuth`**（与 `/market` 一致——`router/index.ts:14-17` 实测 `/market` 只有 `meta.tabbar`）。
- 访客进活动页：看 **banner + 奖品目录**；个人区块（邀请码卡 / 填码卡 / 抽奖主卡 / 积分卡）显示「登录后参与」；点抽奖 / 填码 / 兑换 → 跳 `/login?redirect=/marketing`。
- **⚠️ 新发现：票 02 原型只画了登录态，访客态与「兑换失败态」都没有原型** ⇒ 落地票要自己补这两个态（已写进 spec 的落地注意）。

### 8. 活动 `end_at` 之后的口径（Q8，map 的 Not yet specified）

- 抽奖 / 填码 / 兑换**一律拒**（`ACTIVITY_ENDED`）；`enabled=0` 同理。
- **领奖（claim）不受活动起止约束**：已中奖是既得权益，只由 `user_prize.status` 拦（`PENDING → RECEIVED`）。
- `GET /api/lottery/activity` 的 `status` 供页面切成「已结束」，但奖品目录与我的奖品仍可查。
- 备选「结束 N 天后停止领取」未采纳：本期无物流状态机，越简单越好。

### 9. 文档落地面（Q10，票面 ⑤）

- 照票 04 §9 的先例：**本票只写落地面，实际写文档归落地票**（此刻写就是在描述尚不存在的接口）。
- 落地面 = `backend/docs/api/{points,lottery,referral}.md` + `backend/docs/db/{points,lottery,referral}.md`（照 `backend/CLAUDE.md` §9 的「像代码一样短」+ 关键流程图）。
- **⚠️ 事实自核：三个索引 README 当前不存在**（`backend/docs/README.md` / `backend/docs/api/README.md` / `backend/docs/db/README.md` 均无，仓库根 `docs/README.md` 也无，尽管 §9 要求有）⇒ 落地票**新建**而非「更新」。

### 10. 与票 07 的边界（票面 ⑥）

市集消息页 / 聚合订单页的接口归 **票 07**；本票只覆盖活动页与营销域自身的数据面。

### 决策依据（事实自核，四处修正票面前提）

1. **`backend/docs/README.md` / `backend/docs/api/README.md` / `backend/docs/db/README.md` 当前都不存在**（仓库根 `docs/README.md` 也没有）—— 票面 ⑤ 的「索引更新」实为「新建索引」（见 §9）。
2. **兑换不在积分服务下**：票面 ② 把它列在积分，与票 01 §2·§5、票 04 §3 的既定边冲突（见 §5）。⇒ 积分服务**零写接口**。
3. **票面 ② 列的抽奖失败态「奖品已抽完」不可达**：票 01 §6 定了「命中档库存扣光 → 跳过该档重新归一」，且谢谢参与恒有货 ⇒ 永远抽得出结果。真实失败集 = `ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `NO_CHANCE` / `DAILY_LIMIT_REACHED`（票面漏了后两个）。
4. **`unwrap` 只有一个错误分支**（`front/src/api/http.ts:45` 只看 `code !== '200'`、把 `info` 当 toast 直出），而票 02 的 UI 有两处**必须分支**的失败态（「次数不足」专用弹窗、填码五种文案）⇒ 这才引出 §3 的「业务 code + 前端按 code 分发」。
5. 既有口径实测：分页 `page`/`pageSize`（默认 1/10、上限 50）+ `PageResult{page,pageSize,total,list}`（`WalletController.java:32,57-66`）；管理端一律 `<domain>/admin/...` 子路径 + 控制器内 role 校验（`CreditController.java:62` 的 `/api/credit/admin/adjust`、`ModerationController.java:138` 的 `/api/moderation/admin/words`；`NotificationController.java:60` 是同一手法但路径为 `/api/chat/notifications/broadcast`，**不带 `admin/` 段**）；枚举一律 `.name()` 下发、中文 `desc` 不下发（`ProductController.java:428-431`）；全仓 0 处 `@DubboService` / `@DubboReference`（与票 04 一致）。
6. 库存字段 `sq`/`lq`/`oq` 的语义：`sq` 随合并提交递减、`oq` 同量递增 ⇒ `sq + oq` 在「开桶 / 合并提交 / DB 直扣」三种动作下**守恒**；`lq ⊆ sq` 且是「已拨给 redis」的量。**但 `sq + oq` 不等于总库存**——实物领取会 `oq -= 1`（票 05 §5⑥），每领一件 `sq + oq` 就掉 1。故目录的「y」必须另存一列：**回填票 05 给 `lottery_prize` 加 `total`**（投放总量、建品时定、恒不变），对外 `remaining = sq`、`total = lottery_prize.total`，**不暴露 `lq` / `oq`**（`lq` 是「已拨给 redis」的实现细节，暴露会让运营开一次桶前端「剩余」莫名抖动）。

### 转下游

- **票 07**（2026-09-14 **已 resolved**）：市集消息页 / 聚合订单页的接口与去向（本票 §10 划界）；营销通知 `type` / `refType` 取值（票 04 §5 转来）。
- **票 05 回填**（两处）：① `lottery_draw_record` 补 `client_token` + `uk_user_token(user_id, client_token)`（本票 §4）；② `lottery_prize` 补 `total INT`（投放总量，本票 §2 的「库存对外字段」——目录「剩 x/y」的分母）。
- **落地票**（`.scratch/marketing-impl/`）：三份 api 文档 + 三份 db 文档 + **新建三个索引 README**；访客态与兑换失败态两处补原型；`front/src/api/http.ts` 的 toast 开关。
