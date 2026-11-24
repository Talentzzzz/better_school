# 01: 三处收集段去窗口（队列非空即发）+ 参数退役 + 测试/文档同口径

**Spec:** `backend/docs/architecture/merge-window-removal-spec.md`（Implementation Decisions / Testing Decisions 逐条对应）

**What to build:** 把两套合并引擎的批间等待窗口整体删除——**三处收集段**（`infrastructure/merge` 库存引擎的 `MergeGroup#takeForFlush`、`merge/count` 计数引擎的 `Group#takeForFlush`、`mergevirtual` 的 `VirtualMergeGroup#takeForFlush`）发车条件统一改为「**队列非空即发（唯一条件）**」；「窗口到点」条件删除，**「攒满 maxBatch 提前发车」也删除**（`maxBatch` 只保留单批上界语义），停机只管停收与排空、不再参与发车判定。窗口参数 `maxFlushDelayMillis` 降级为退役兼容参数（yml 键保留一个版本、读入不参与发车判定）。

三处必须同票落地（防漂移纪律：任何一处只改自己 = 违规）。执行权协议、claim-CAS、supervisor、退出谓词、停机三步、背压口径**一行不动**。

**Blocked by:** None (can start immediately)

**Status:** resolved (2026-11-24)

- [x] 三处收集段发车条件改为「队列非空即发」；窗口计时变量（`windowNanos` / `windowDeadline`）与「攒满触发」判断全删
- [x] 进件必须能立刻叫醒空等的 leader：请求线程版与虚拟线程版 `enqueue` 无条件 `ready.signal`；计数引擎新增 collector 专用条件（`arrival`）承接「进件 / 落库结束」两种唤醒，`ready` 只留给 follower，避免每进一件惊醒全部 follower（惊群）
- [x] 探测间隔保留并改写理由：它只是「队列空时的信号丢失兜底」，不是收集窗口
- [x] 两份 `MergeConfig` 的 `maxFlushDelayMillis` javadoc 标注退役 + 决策出处（本 spec），校验保持 `>= 0`；计数侧 `waitTimeoutMs > maxFlushDelayMillis` 耦合删除（窗口退役后两者正交），改为 `waitTimeoutMs > 0`
- [x] 三份收集段 javadoc 补「为什么没有窗口」：内核对照（收集窗 = leader 持行锁处理的时间本身、提交即清空立即下一轮）+ 两条结构安全性论证（事务速率 = 1/flush ≤ DB 天花板；合并比 = 到达速率 × flush 耗时自动伸缩）+ 双引擎并存定位（请求线程版 = 通用后备、虚拟线程版 = 有虚拟线程环境的终态）
- [x] `application.yml` 两个键的注释改为「退役兼容键，读入不参与发车判定」；`CountMergeConfig` / `MergeEngineConfig` 启动日志与 javadoc 同步（不再宣传「收集窗口」）
- [x] 断言同步改写（两包对称）：「窗口到点才发车（单件 ≈ 窗口）」→「单件立即落库（返回 ≈ 一次 flush）」；「攒满提前发车」用例删除，换成「队列有货就接着发（在途期间进件，落库结束立即发下一批，单批 ≤maxBatch）」；靠窗口「把件扣在队列里」的既有用例（保序 / 停机排空 / 容量满即拒）改用**闸门后落库**制造确定的在途与排队
- [x] 既有两份引擎 spec 的「收集窗口同口径平移」条款标注作废并指向本 spec；`backend/CLAUDE.md` 技术包树补发车口径一句
- [x] 全 reactor `test-compile` 绿；**两包三处引擎单测实跑 82 例全绿**（实测 surefire 可离线跑纯内存用例；计数侧已补齐两条对称用例）
- [x] 票外发现的三个存量缺陷已修（详见 spec「实现记录」）：计数引擎退出时带走收集领导权、`accepted/rejectedFull/rejectedClosed` 从不累加、`Pending` 终态两步写竞态
- [ ] 欠账（留给下一个人）：`bs-app` 集成测试（需 MySQL/Redisson）本票未跑；**调用方切换 / 旧包退役 / 任何替换决策之前，必须先补跑 merge + merge/count + mergevirtual 全套单测并全绿**
