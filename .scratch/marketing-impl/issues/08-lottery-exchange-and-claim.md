# 08: 抽奖服务·兑换与领奖

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「数据模型与行级时序」第 5 / 6 条「接口口径与失败语义」+ §「我的奖品 / 中奖记录」

**What to build:** 抽奖服务上的两条写边与一条领取边：用积分兑抽奖次数、用积分兑奖品（都要同步 Dubbo 调积分服务扣分）、我的奖品三态列表与实物领取表单。

**兑换的两个写接口挂抽奖、不挂积分**（票 01 §2·§5 的既定边）——前端打抽奖，抽奖同步 Dubbo 调积分扣分。

**Blocked by:** 05（兑换要 Dubbo 打积分）、07（实物领取要 07 建的 `lottery_user_prize` 与库存机制）

**Status:** resolved

- [x] `POST /api/lottery/exchange/draw-count`：时序照 spec 第 5 条——先插 `lottery_redeem_order(PENDING)` **拿到幂等键** → Dubbo `IPointsExchangeService` 扣分 → 本地事务（加抽奖次数桶）→ 置 `DONE`
- [x] `POST /api/lottery/exchange/prize` body `{prizeId}`：同一条边，本地事务换成「扣库存 + 落 `lottery_user_prize(PENDING)`」
- [x] **重试用同一个 `redeemOrderId`**，两侧幂等键各自跳过；**不做真补偿**，极端残留停在 `PENDING` 供人工对账
- [x] 抽奖服务 pom 直接依赖 `com.betterschool:points-api`；域内 `IPointsExchangePort` + infra `@DubboReference(check = false)` 实现
- [x] `GET /api/lottery/my-prizes?status=PENDING|RECEIVED|CREDITED`（三段 tab 的映射），`created_at DESC`
- [x] `POST /api/lottery/my-prizes/{id}/claim` body `{receiver,mobile,address}`：`PENDING → RECEIVED` + 落收件信息 + **`oq -= 1`**（没有物流状态机，「领取」是唯一可落账时点）
- [x] **claim 不受活动起止约束**——已中奖是既得权益，只由 `user_prize.status` 拦；「积分入账」tab（`CREDITED`）**不回填收件信息**
- [x] 领取表单校验：收件人 `@NotBlank @Size(max=30)`、手机号 `^1\d{10}$`、地址 `@NotBlank @Size(max=200)`（中文 message，走 `GlobalExceptionHandler` → `400`）
- [x] **兑换定价归抽奖**：次数价 = `lottery_activity.draw_count_price`、奖品价 = `lottery_prize.redeem_points`，随两个免登录 GET 返回，**不单做定价接口**
- [x] 失败码 + 固定中文文案：`ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `INSUFFICIENT_POINTS` / `PRIZE_NOT_REDEEMABLE` / `OUT_OF_STOCK`（兑换）、`PRIZE_NOT_CLAIMABLE`（领奖）
- [x] 验收：积分够 → 兑次数成功后次数余额涨、兑换单 `DONE`、积分流水少一笔；积分不够 → `INSUFFICIENT_POINTS`；**同一个 `redeemOrderId` 重试不重复扣分、不重复加次数**；兑奖品后 `my-prizes` 三态正确、领取后 `oq` 减 1 且 `total` 不变
- [x] 验收口径（票 01 §4 反转后）：**积分服务停掉时，抽奖（抽一次）与成团照跑，只有兑换不可用**

## 实现要点（票 08）

### 落了什么

| 面 | 落点 |
| --- | --- |
| SQL | **一列没加**——`lottery_user_prize` / `lottery_redeem_order` / `lottery_prize.redeem_points` 票 07 已建好，本票只把它们用起来（所以 `schema-lottery.sql` / `023_lottery.sql` 都没动，测试库也不必重建） |
| types | `ResponseCode` 续 4 个码（4306~4309）；新枚举 `RedeemOrderKind` / `RedeemOrderStatus` / `PointsExchangeReason` |
| api | 6 个 DTO（`UserPrizeResponseDTO` / `ClaimPrize{Request,Response}DTO` / `RedeemPrize{Request,Response}DTO` / `RedeemDrawCountResponseDTO`）+ `ILotteryService` 四个新方法 |
| domain | `IPointsExchangePort` + `PointsDeductVO` / `PointsDeductFailure`（跨服务只传语义、不传对端码）；`RedeemOrderEntity` / `UserPrizeVO` / `ExchangeResultVO` / `ClaimFormVO`；`IRedeemOrderRepository` + `IUserPrizeRepository` / `IPrizeRepository` 扩展；`LotteryExchangeServiceImpl`、`UserPrizeServiceImpl` |
| infrastructure | `PointsExchangePort`（`@DubboReference(check = false)`）；`RedeemOrderRepository`、`UserPrizeRepository` / `PrizeRepository` 扩展；`ILotteryDao` + `RedeemOrderPO` / `UserPrizeViewPO` + `LotteryMapper.xml` 追加 |
| pom | 父 pom 加 `points-api` 的 dependencyManagement；`lottery-infrastructure` 依赖 `points-api`（兑换边是「三服务零编译期依赖」的既定例外，票 01 §4 反转） |
| trigger | `LotteryController` 四个端点：`GET /my-prizes`、`POST /my-prizes/{id}/claim`、`POST /exchange/draw-count`、`POST /exchange/prize` |
| 测试 | `StubPointsExchangePort`（@Primary，照积分侧口径建模：余额 / 幂等键 / 调不通三态）+ 两个测试类 **20 个用例** |
| 文档 | `docs/api/lottery.md`（四个接口 + 4xxx 续码表）、`docs/db/lottery.md`（三处写路径口径）、`docs/testing.md`（抽奖这一格的 Dubbo 桩） |

### 时序与口径（照 spec 第 5 / 6 条）

1. **兑换**：活动闸门 + 可兑预检 → 插 `lottery_redeem_order(PENDING)` 拿幂等键 → Dubbo 扣分 → 本地事务（加次数桶 / 扣库存 + 落待领取）**与置 `DONE` 同一个事务** → 返回。
2. **兑奖品的库存走「与抽奖完全相同的那两条通路」**（票 05 §5⑤）：redis 池 `DECR` 优先，连不上切 DB 直扣兜底 + 10s 冷却。理由：票 07 已把「非池库存只是 redis 故障时的储备」写进口径，兑换若单开一条只吃非池的路，开桶拨走 80% 之后就只剩 20% 可兑，还跟目录的 `remaining = sq` 对不上。
3. **以单号为锚的重放**（域服务 `resume`）：积分侧按 `refId` 跳过、本地侧按次数桶 uk / 我的奖品 uk 跳过，只补齐未完成的那步；**不做真补偿**，残留停在 `PENDING` 供人工对账。
4. **领取**：`PENDING → RECEIVED` + 落收件信息 + `oq -= 1`，`total` 不动；**不看活动起止**，唯一闸门是 `user_prize.status`。

### 动手时定的两件事（票面之外，各留了理由）

1. **「重试」在 HTTP 侧的落点**：票 06 把请求体形状定死了（兑次数体空、兑奖品只有 `prizeId`），客户端**上送不了单号**，所以「重试用同一个 `redeemOrderId`」只能由服务端把「同一意图」认出来——`IRedeemOrderRepository.findPending(userId, kind, prizeId)` 找到那张没做完的单就接着做完，**不新开一单**（新开一单 = 给积分侧换个 refId 再扣一次）。同 kind + 同 prizeId 即同一意图，先来先做完。这条同时把 `resume()` 从「只有测试调得动」变成真入口（code-review Spec 轴揪出）。
2. **`oq -= 1` 是 best-effort、不拦领取**：池内扣减的 `oq` 靠**合并提交**才加上去（票 05 §5④），所以「刚中奖 / 刚兑换就领」那一个秒级窗口里它还是 0。若拿它当闸门 ⇒ **xxl-job 的合并提交没在调度中心配起来时实物永远领不了**（票 07 已把 job 的 cron 留在部署侧）。取舍：放行 + `log.warn`，代价是这种极窄窗口里 `oq` 会多记 1（`oq` 不对前端暴露，对外只有 `remaining = sq` 与 `total`）。用例 `claimSucceedsBeforeMergeHasRun` 把这个取舍钉住并写明。
   - 没选「在领取里顺手跑一次合并提交」：`UserPrizeServiceImpl` 注入 `IDeductMergeService` 会撞 `backend/CLAUDE.md` §1「domain 服务禁止互相注入」。

### code-review 采纳项

两轴并行（Standards / Spec），采纳 5 条：

1. **Spec**：`resume()` 没有 HTTP/job 入口 = 生产上是死代码 ⇒ 见上「动手时定的第 1 条」。
2. **Spec**：预检把「池空 + 非池储备还在」放过了（redis 好好的时候储备根本卖不出去）⇒ 会造成「扣了分拿不到奖品」。改成 `poolReady || (降级中 && 非池可卖)`，并补用例 `exchangePrizeRejectsWhenPoolEmptyEvenIfReserveRemains`。
3. **Spec**：`UserPrizeResponseDTO.source` 不在票 06 §2 的响应契约里 ⇒ 删字段与对应用例断言。
4. **Standards**：`requireOngoing` 抽奖与兑换各一份 ⇒ 收到 `ActivityConfigVO.requireOngoing(now)`（规则本来就约束这份配置），`LotteryDrawServiceImpl` 里的私有副本一并删掉。
5. **Standards**：`requireStock` 在调用方重述 `sq - lq > 1` ⇒ 收到 `PrizeEntity.canSellFromReserve()`；`PointsDeductVO.balance` 声明「留着对账」却没人读 ⇒ 扣分成功时真打一条带 `balance` 的日志；`IUserPrizeRepository.claim` 把 `ClaimFormVO` 拆成三个参数 ⇒ 改回传值对象。

未采纳（留理由）：

- **`releaseQuietly` / 降级回退那段与抽奖重复**：骨架像但落点不同（抽奖要滚档重掷、兑换是指定奖品），抽出来的抽象会把两条不相干的路径塞进同一入口；与方法体同为 5~10 行的重复相比，宁可留两份（根 `CLAUDE.md`：「Three similar lines is better than a premature abstraction」）。
- **`ExchangeResultVO.drawCount` 可空**：兑次数那条要「兑完顺手把新余额回给前端」，兑奖品没有；一个可空字段比两个 DTO、或让前端多打一次 `draw-chances` 更省事。
- **`bizId` / `refId` / `sourceRefId` 三个名字**：分别对着三张表的列（`lottery_deduct_detail.biz_id` / 积分侧 `ref_id` / `lottery_user_prize.source_ref_id`），统一改名会与库表脱节。
- **`PointsDeductVO` 只带语义不带对端码**：积分侧目前只有 `4101` 一个可展示的失败，把 4xxx 码透到域层等于让域认识对端枚举；`UNAVAILABLE` 一类一律落 `999`。

### 验证怎么做的

```bash
cd backend/lottery && mvn -o test
```

- **50 个用例全绿**：目录 5 + 单抽 14 + 奖金池与合并 6 + 降级直扣 5（票 07 的 30 个**无回归**）+ **兑换 14 + 我的奖品与领取 6**（本票）。
- 本票新增覆盖：兑次数成功（余额涨 / 单 `DONE` / 流水少一笔）、积分不足、活动三态闸门、`resume` 两种残留（已落账 / 根本没落账）、**HTTP 重试复用未完成的单**（兑次数 + 兑奖品各一条，钉住「不新开一单、不重复扣分、不重复占额度」）、兑奖品落 PENDING 待领取 + 池内扣减 + 合并提交后 `sq/lq/oq`、不可兑换档、没上架、池空、池空但储备在、活动已结束、积分服务停掉时抽奖照跑、三段 tab 且只看自己、领取落收件信息 + `oq` 减 1 + `total` 不变、非本人 / 已领 / 积分入账 / 不存在四类 `PRIZE_NOT_CLAIMABLE`、活动结束后仍可领、表单三类校验的中文文案、**合并提交之前领取**。
- **「积分服务停掉」的验收**用 `StubPointsExchangePort.switchToUnavailable()` 表达「Dubbo 调不通」：兑换返回 `999`，同一进程里 `POST /draw` 照常成功。
- **单体整条 reactor 全量回归**：`cd backend && mvn -o test` → **335 用例全绿**（本票只动 `backend/lottery/**` 与 docs，单体侧零改动，跑一遍确认没被波及）。
  ⚠️ 踩坑记一笔：`mvn -o test -pl bs-app`（**不带 `-am`**）会从本地仓库拿旧 jar，跑出 216 个 `MyBatisSystemException` / `ReflectionException` 幻影失败（`NotificationPO` 没有 `scope` 之类），**与本票改动无关**——本仓跑后端测试一律带 `-am` 或直接跑整条 reactor。

### 已知波及 / 不在本票范围

- **合并提交 job 的 cron 仍是部署侧的事**（票 07 遗留）：领取不拦 `oq == 0` 就是为它兜的底。
- **并发双击兑换**会各开一单、各扣一次分——同一瞬间的两个请求在服务端是两个「同一意图的新请求」，没有客户端 token 无法区分（请求体形状由票 06 定死）。**顺序重试**（失败后再点一次）已被 `findPending` 覆盖。
- **积分服务真停服、真跨进程 Dubbo 调用**未验（桩表达）；归联调与票 09。
- **兑奖品的 DB 直扣兜底分支**只在 redis 降级时进入，本票没专门用例（票 07 的降级用例已覆盖同一段 SQL 与同一个哨兵）。
- **前端**（活动页兑换按钮、我的奖品三段 tab、领取表单）归票 11；三个定价字段（`drawCountPrice` / `redeemPoints`）票 07 已随免登录读接口返回，票 11 直接取用。
