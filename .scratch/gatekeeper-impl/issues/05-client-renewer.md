# 05: 客户端续租通道（单分片端到端闭环）

**What to build:** spec §2 生命周期 3/5/6 步 + §7 降级矩阵的客户端侧，与票 03/04 合起来即「单分片端到端」。端到端行为：**SDK 起来后，首个请求同步合批首租拿到份额建桶，之后守护虚拟线程周期续租维持租约；杀掉 center，客户端继续按最后份额放行（fail-open）；中心下发 `ttl_ms = 0` 则停止自补、存量花完——两种断租形态可区分**。

机制逐条照 spec §2 / §6.3 / §7：

- **同步首租**：首个带 key 的请求在调用方线程同步首租一次——进合批窗口（默认 10ms）、端到端预算 50ms、重试复用同一幂等键、仍失败按三分法拒（有规则但首租失败 ⇒ 拒 + 记指标，不分 fallback 档）；按分片拆子批的形状本票先按单分片落（真拆批归票 07）。
- **renewer**：一条守护虚拟线程每 `refresh_interval`（默认 5s，加抖动）醒来，把在册 key 的增量用量三字段（`time_elapsed_ms` / `allowed` / `denied`）`POST /v1/lease`（每子批一个幂等键）；每子批超时 2s、周期内最多重试 1 次（退避 ~1s + 抖动），仍败走 fail-open。
- **份额落地（spec §2 第 5 步）**：份额没变 → 本地桶不动、只延有效期；变了 → 立即生效 + rescale（`stored × newMax / oldMax`，票 01 已有）。
- **两态断租**：`ttl_ms = 0`（主动）⇒ 停止自补（份额视作 0）、存量允许花完；被动超时/5xx ⇒ 继续按最后份额自补。fail-closed 规则断租 ⇒ 停止自补、存量花完（规则级 `fallback` 只作用「曾有租约」情形）。
- **优雅关停**：关停钩子 best-effort `POST /v1/lease/return` 一次、不等响应、失败即弃。
- **规则变更回调 → 立即续租**（spec §5 第 2 条）：复用客户端侧抖动防惊群——「热更新立即生效」的后半截在此接上。
- **`unmatched` 消费**：收到 ⇒ 停止续租 + 逐出在册条目 + 该 key 放行（spec §5 规则删除收敛）。
- 客户端 `/metrics` 基础计数随手带（放行/被拒/续租失败降级计数——完整 MXBean 归票 09）。

**Blocked by:** 03、04（中心两端点与三值状态、真分配算法就位）

**Status:** resolved

- [x] 端到端 IT：首租合批 → 建桶放行 → 周期续租 → 份额变化 rescale 生效，一轮 `refresh_interval` 内收敛
- [x] fail-open IT：kill center 后继续按最后份额放行；center 回来 ≤ 1 个 `refresh_interval` 收敛
- [x] 两态断租 IT：`ttl_ms=0` 停止自补 vs 被动超时继续自补，行为可区分
- [x] `unmatched` IT：中心回未匹配 ⇒ 一个 `refresh_interval` 内逐出 + 放行、无拒绝窗口
- [x] 首租三分法 IT：有规则但首租失败 ⇒ 拒 + 指标；无规则 ⇒ 放行；优雅关停 return 被发出
- [x] `mvn test` 全绿

## Answer

**落地点**（全在 `gatekeeper-client`，包内平铺、package-private——不套 DDD 是 `gatekeeper/CLAUDE.md` §1 对库模块的纪律）：

| 新增 | 装什么 |
|---|---|
| `LeaseChannel` | 连中心的那一半：组装传送门/首租通道/renewer，挂关停钩子，`close()` 停续租 + best-effort 归还 |
| `FirstLeaseBatcher` | 同步首租：合批窗 + 端到端预算 + 同幂等键重试 1 次 + 按 500 拆子批；结局三态（`GRANTED`/`UNMATCHED`/`FAILED`）而不是布尔 |
| `Renewer` | 守护虚拟线程：`refresh_interval`（+抖动）醒来、增量三字段按 500 拆子批、每子批超时 2s、退避 ~1s 重试 1 次、仍败 fail-open；规则变更回调触发带抖动的立即续租 |
| `LeaseApplier` | 三态落地（首租通道与 renewer 共用这一份）：`granted` 动桶 / `unmatched` 逐出 / `throttled` 什么都不做；断租时按规则 `fallback` 决定 fail-closed 停自补 |
| `LeaseTransport` | JDK `HttpClient` 走两个端点；归还 fire-and-forget（`sendAsync` 不 join）；**进程冷启动预垫**（见下） |
| `ClientConstants` / `ClientStats` / `InstanceIdentity` / `LeaseTransportException` | 拍定默认值 / 四个基础计数 / 身份（`POD_NAME`→`HOSTNAME`→IP + 启动 UUID）/ 单一失败类型 |

改动：`GatekeeperClient` 加 Builder 旋钮（`center` / `instanceId` / `refreshInterval` / `renewTimeout` / `firstLeaseBatchWindow` / `firstLeaseBudget`）、冷 key 走首租、`close()`（`AutoCloseable`）、规则回调兼触发立即续租；`LocalBucket` 加**上报窗口**（`pendingUsage` 只看不清 / `commitUsage` 结算才清、`stopRefill()` 冻结自补不 rescale、租约到期时刻）与等待循环用的 `tryAcquire(now, countUsage)`；`BucketTable` 加 `snapshot()`——**桶表就是「在册」名单本身**，不另养一张注册表（两份必然要互相对齐，漏一处就是"有人报用量没人续"或"永远续不上"）。`gatekeeper-client` 新增 `jackson-databind`（自己构请求/解响应；不是 Spring、不是观测依赖，enforcer 的三条纪律不受影响）。

**实测读数 / 平台事实（本票最值钱的两条）**：

1. **进程内首次 HTTP 往返 ~400ms**：JDK `HttpClient` 在**一个进程里的第一次**往返要付一次性初始化，本机实测 **~400ms**（同进程第二个客户端实例首次仅 **~7ms**）——比首租那 50ms 预算大一个量级。⇒ SDK 起来时后台预垫一次往返（`GET /v1/lease`：真中心只有 POST 映射 ⇒ `405`，不改 gatekeeper 域内状态与计数）。**残余风险**：SDK 起完立刻就有冷 key 到达（中间没有间隔）时，那一批仍按 §7 三分法被拒一次——方向安全（拒而非超发），但发版后头一批冷 key 会被拒一回。已回填 spec §6.3。
2. **本机 loopback 的首次连接 ~30ms**：所以 IT 里把首租预算显式给宽（500ms）——spec §6.3 的 50ms 是**产品口径**、标定归票 10 的 harness（"首租端到端 p99 ≤ 50ms"），IT 测的是通道**语义**。
3. 踩坑留档：假中心回空体要用 `sendResponseHeaders(code, -1)`；传 `0` 是 chunked，随即关流会把连接搞坏、复用它的下一个请求拿到 `EOF`（报成客户端的 "HTTP/1.1 header parser received no bytes"）。

**自行拍板的空白（四处，都可反转）**：

1. **首租那个同步请求报什么窗口**（票 04 交接的三件事之一）：报**"这个 key 从被看见到合批发出"的观测窗口**，`time_elapsed_ms` 至少记 1ms。理由：中心的实测需求 = 计数 ÷ 经过时间，报零（或 `time_elapsed_ms = 0`，`DemandVO` 明确读成实测 0）会让**每个冷 key 被饿一个 `refresh_interval`**；报观测窗口把"这个 key 正被真的打"带上去，估高的方向由 `minF` 封顶且下一轮就按真实窗口收敛。**被否的两条**：延后建桶（首个请求必拒，与 §9 演示口径"半秒 10 个放行 ≤ 3"、§8.5"首租合批 → 建桶放行"直接冲突）、拿规则 `rate` 当 `wants` 报上去（`wants` 是应用自声明的配置基线，SDK 替应用声明等于把每台实例都写成"我要整个 R"，会把 §3.4 明拒的静态 `R/N` 饿死热实例那一路复活）。已回填 spec §3.5。
2. **单分片先落**：`center(URI)` 一个地址，真按槽位拆批与多地址归票 07（那时这里变成列表 + 拓扑感知的地址选择）。
3. **`unmatched` 逐出后不在本地缓存**：本地规则还没跟上（配置源 1–2s 轮询）时，后续请求仍会走一次首租往返——中心还回 `unmatched` ⇒ 放行（无拒绝窗口）；真遇到中心不可用 ⇒ 按 §7 三分法拒（本地规则说过"该限"，这是它该有的后果，不是新拒绝窗口）。收敛靠两端直连的同一份配置源。
4. **每个 client 一个关停钩子**（不是进程一个）：进程只留一个钩子需要静态状态，而每个 client 都得归还自己那份在册 key；钩子幂等——已 `close()` 或没有在册 key 时是空操作（测试里建很多 client 也不会每次都去打中心）。

**已知代价 / 波及**：

- **冷启动风暴**：合批窗内所有冷 key 进一个请求，超 500 拆子批、且整体受端到端预算约束；预算内发不完的子批算失败，下个窗口重来——这正是背压（也是"不该按 key 单独打枪"的原因）。
- **多子批时失败重试逐子批串行**：全败的一轮可能超过一个 `refresh_interval`（fail-open 语义不变，只是下一轮推迟）。票 07 拆批时一并复核。
- `acquire` 对"命中规则的冷 key"会最多触发两次首租尝试（`deniedNoShare` 的等待时长是"永远"，睡满超时后再判一次）。
- **未做**：`/metrics` 只有 `ClientStats` 四个计数（包内可见，MXBean + `GatekeeperObserver` SPI + 桶水位/等待分布/逐出计数归票 09）；分片 / `421` / 拓扑归票 07；`wants`（配置基线）在客户端仍没有入口，等票 08 的接入层形状。

**code-review 采纳项**（两轴，`/code-review` 对着 `HEAD` 的工作区改动）：

- **Spec 轴（两条是真货）**：① **`ttl_ms = 0` 的首租建了桶却没拉续租线程**（`onLeaseLanded` 只挂在 `ttl > 0` 那一支）——那条桶停着自补又没人替它问中心，是**走不回来的死路**，与 spec §7「下次续租带回新份额即恢复」直接冲突 ⇒ 两支都拉线程，并补一条"没有它就会挂"的回归用例（已实测：改回旧写法该用例失败）。② **关停不是终态**：`close()` 之后一条晚到的落地（`setShare` / 在飞的首租落地）会把续租线程又拉起来，与 `close()` 的承诺"不再有人替它续租"矛盾 ⇒ `Renewer` 加 `stopped` 终态（同样补了会挂的回归用例）。③ `throttled` 这一格（§6.2 三态之一、§7 三分法第二格）**原先没有任何用例** ⇒ 补 `respondThrottled()` + 用例（拒且不建桶）。④ spec §6.3 那句"不进任何计数"不准确（中心挂了 actuator，通用 HTTP 指标照样算它一笔）⇒ 改成"gatekeeper 域内不改状态、不记计数"。⑤ fail-open 用例名说"kill center"而实际是 `503` ⇒ 改名"中心不可用"（真·进程级 kill 归 spec §8.5 的 chaos 脚本 + 人工走查，用例 javadoc 已写明）。
- **Standards 轴**：① `Batch.sealed` 写了没人读（"封口"其实是同一把锁下摘 `current` 那一步）⇒ 删字段与 `seal()`，把不变量写进 `joinOrCreate` 注释；② 首租与续租**各写了一份** `new LeaseRequest(identity…, version, UUID, keys)`（连注释都逐字重复）⇒ 收成 `InstanceIdentity.leaseRequest(...)`；③ `LeaseTransport.post` 的 `what` ⇒ `operationLabel`；④ `setShare` 的 javadoc 说"正常路径由续租通道调用"，而续租走的是 `LeaseApplier`（它还管 `ttl = 0` 与到期时刻）⇒ 改写成"手工口"；⑤ `InstanceIdentity` 的 `@param … 非空白` 与 `of()` 不校验自相矛盾 ⇒ Builder 与 `of()` 都挡掉空白身份；⑥ `BucketTable` 注释里的"注册表"撞 `CONTEXT.md` 的 _Avoid_ 词 ⇒ 换词；⑦ `StubCenter.resume()` 没人调 ⇒ 删。
- **两轴都点到、判为 judgement call 不采纳**：`LeaseChannel` 上两个纯转发方法（它本来就是**组装**那一层，把 renewer 漏给 client 只会让连线散到两处）；首租/续租各有一个 `switch (status)`（两个 switch 的 **body 不同**——一个只落地，一个还要给等待者记结局；合并得让 `LeaseApplier` 反过来依赖 batcher 的类型）；四个类共用的那串构造参数（数据团——照票 04 对 `LeaseCase` 10 参数的先例，等票 09 归拢观测时一起收）；`ClientStats` 仍包内可见（票面明说完整 MXBean 归票 09）。

**验收**：`mvn test` 175 测试全绿（protocol 25 + config 24 + client 63 + center 33+25+5），client 侧新增 26 个（首租通道 7、续租通道 10、桶上报窗口 7、等待循环窗口 1、关停终态 1）。IT 全部跑真 HTTP（`StubCenter`：真起 `com.sun.net.httpserver`、真过 JSON），假中心只决定"答什么"（份额变化 / `ttl = 0` / `unmatched` / `throttled` / `503` / 挂着不回）——真中心由票 03/04 的端点测试与票 11 的 IT 覆盖。
