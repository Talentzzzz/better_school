# RocketMQ 常驻 flush 线程实现级参照（ServiceThread / GroupCommitService）

> 研究工单：001。为库存合并器"每分片常驻 flush 线程"重构（ShardFlusher：ReentrantLock + Condition awaitUntil + signal、常驻平台线程、signal + join 停机）提供一手源码参照。
>
> 来源分支：apache/rocketmq `develop`（截至 2026-06 issue #10543 修复之后）。
> 所有代码均为逐行摘录，未改写。

---

## 1. ServiceThread 修复版（apache/rocketmq develop）

- 文件：`common/src/main/java/org/apache/rocketmq/common/ServiceThread.java`（注意：没有 thread 子目录，就在 common 根下）
- raw URL：https://raw.githubusercontent.com/apache/rocketmq/develop/common/src/main/java/org/apache/rocketmq/common/ServiceThread.java
- 修复背景：#10543（见第 4 节）——此版本已弃用 CountDownLatch2，改为 `LockSupport.parkNanos` + deadline 循环 + `hasNotified` 原子标志。

### 1.1 成员变量

```java
public abstract class ServiceThread implements Runnable {
    private static final long JOIN_TIME = 90 * 1000;
    protected volatile Thread thread;
    protected final AtomicBoolean hasNotified = new AtomicBoolean(false);
    protected volatile boolean stopped = false;
    protected boolean isDaemon = false;
    //Make it able to restart the thread
    private final AtomicBoolean started = new AtomicBoolean(false);
```

要点：`hasNotified` 是"粘性许可"的软件化身；`thread` 是 volatile，因为 wakeup 线程与 worker 线程并发读写。

### 1.2 wakeup()：unpark + CAS 粘性许可

```java
    public void wakeup() {
        if (hasNotified.get()) {
            return;
        }
        if (hasNotified.compareAndSet(false, true)) {
            LockSupport.unpark(this.thread); // notify
        }
    }
```

### 1.3 waitForRunning(interval)：park/unpark + deadline 循环（修复核心）

```java
    protected void waitForRunning(long interval) {
        // Publish the parking thread so wakeup() can target it (also handles restart).
        this.thread = Thread.currentThread();
        if (hasNotified.compareAndSet(true, false)) {
            this.onWaitEnd();
            return;
        }
        // LockSupport permits are sticky: an unpark delivered before park makes the next park
        // return at once, and the loop re-checks hasNotified, so no wakeup can be lost.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);
        while (!hasNotified.get()) {
            if (stopped) {
                break;
            }
            long remain = deadline - System.nanoTime();
            if (remain <= 0) {
                break;
            }
            LockSupport.parkNanos(this, remain);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        hasNotified.set(false);
        this.onWaitEnd();
    }
```

**"unpark 许可粘性 + 循环重查保证唤醒不丢"机制解析**：

1. **许可粘性（sticky permit）**：`LockSupport.unpark(t)` 若在 `park` 之前发生，许可被保存，下一次 `park` 立即返回。这与 `wait/notify`（notify 无粘性，等在 wait 之前发出会丢）有本质区别——这是把 countDown/reset 竞态彻底消灭的底层保证。
2. **循环重查**：每次从 park 醒来（无论被 unpark、超时还是 spurious wakeup）都回到 `while (!hasNotified.get())` 重查标志，且 deadline 是整个等待期共享的（parkNanos 只睡剩余时间），既不会因假醒提前失效超时，也不会漏掉已投递的许可。
3. **hasNotified 双重角色**：wakeup 端 CAS(false→true) 去重并发唤醒；worker 端在进入等待前 CAS(true→false) 走快速路径（不 park 直接干活），离开等待时 `set(false)` 复位。即使标志在复位瞬间又被置 true，粘性许可也会让下一次 park 立即返回，最终由循环重查兜底。
4. **`this.thread = Thread.currentThread()`**：park 目标线程必须在 park 前发布，否则 unpark 可能打到旧线程引用上（restart 场景同样处理）。

---

## 2. GroupCommitService（CommitLog 内部类）

- 文件：`store/src/main/java/org/apache/rocketmq/store/CommitLog.java`
- 类结构：`GroupCommitService extends FlushCommitLogService`，后者 `extends ServiceThread`（与异步刷盘的 `FlushRealTimeService` 平级，由 SYNC_FLUSH 刷盘类型选用）。

### 2.1 字段：双 list + 自旋锁

```java
    class GroupCommitService extends FlushCommitLogService {
        private LinkedList requestsWrite = new LinkedList<>();
        private LinkedList requestsRead = new LinkedList<>();
        private final PutMessageSpinLock lock = new PutMessageSpinLock();
```

### 2.2 putRequest：生产端只加锁写 write list，然后 wakeup

```java
    public void putRequest(final GroupCommitRequest request) {
        lock.lock();
        try {
            this.requestsWrite.add(request);
        } finally {
            lock.unlock();
        }
        this.wakeup();
    }
```

### 2.3 swapRequests：O(1) 双缓冲交换

```java
    private void swapRequests() {
        lock.lock();
        try {
            LinkedList tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        } finally {
            lock.unlock();
        }
    }
```

### 2.4 doCommit：消费端无锁遍历 read list

```java
    private void doCommit() {
        if (!this.requestsRead.isEmpty()) {
            for (GroupCommitRequest req : this.requestsRead) {
                boolean flushOK = CommitLog.this.mappedFileQueue.getFlushedWhere() >= req.getNextOffset();
                for (int i = 0; i < 1000 && !flushOK; i++) {
                    CommitLog.this.mappedFileQueue.flush(0);
                    flushOK = CommitLog.this.mappedFileQueue.getFlushedWhere() >= req.getNextOffset();
                    if (flushOK) {
                        break;
                    } else {
                        // When transientStorePoolEnable is true, the messages in writeBuffer may not be committed
                        // to pageCache very quickly, and flushOk here may almost be false, so we can sleep 1ms to
                        // wait for the messages to be committed to pageCache.
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                req.wakeupCustomer(flushOK ? PutMessageStatus.PUT_OK : PutMessageStatus.FLUSH_DISK_TIMEOUT);
            }
            long storeTimestamp = CommitLog.this.mappedFileQueue.getStoreTimestamp();
            if (storeTimestamp > 0) {
                CommitLog.this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(storeTimestamp);
            }
            this.requestsRead = new LinkedList<>();
        } else {
            // Because of individual messages is set to not sync flush, it
            // will come to this process
            CommitLog.this.mappedFileQueue.flush(0);
        }
    }
```

### 2.5 run() 主循环 + onWaitEnd 挂钩

```java
    @Override
    public void run() {
        CommitLog.log.info("{} service started", this.getServiceName());
        while (!this.isStopped()) {
            try {
                this.waitForRunning(10);
                this.doCommit();
            } catch (Exception e) {
                CommitLog.log.warn("{} service has exception. ", this.getServiceName(), e);
            }
        }

        // Under normal circumstances shutdown, wait for the arrival of the
        // request, and then flush
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            CommitLog.log.warn("GroupCommitService Exception, ", e);
        }
        this.swapRequests();
        this.doCommit();
        CommitLog.log.info("{} service end", this.getServiceName());
    }

    @Override
    protected void onWaitEnd() {
        this.swapRequests();
    }
```

**结构要点**：交换发生在 `onWaitEnd()`（即被唤醒/超时之后、doCommit 之前），所以消费线程处理 read list 期间，生产端可以继续无阻塞地往 write list 添加；锁只在 add 与 swap 两个 O(1) 点上短暂持有，doCommit 全程无锁。

---

## 3. 停机模式：stopped + wakeup + join

```java
    public void shutdown(final boolean interrupt) {
        log.info("Try to shutdown service thread:{} started:{} lastThread:{}", getServiceName(), started.get(), thread);
        if (!started.compareAndSet(true, false)) {
            return;
        }
        this.stopped = true;
        log.info("shutdown thread[{}] interrupt={} ", getServiceName(), interrupt);
        //if thead is waiting, wakeup it
        wakeup();
        try {
            if (interrupt) {
                this.thread.interrupt();
            }
            long beginTime = System.currentTimeMillis();
            if (!this.thread.isDaemon()) {
                this.thread.join(this.getJoinTime());
            }
            long elapsedTime = System.currentTimeMillis() - beginTime;
            log.info("join thread[{}], elapsed time: {}ms, join time:{}ms", getServiceName(), elapsedTime, this.getJoinTime());
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        }
    }
```

顺序是关键：先置 `stopped`，再 `wakeup()`（保证 worker 从 park 醒来后第一件事就看到 stop 标志），最后 join（带 90s 上限）。另有 `makeStop()` 只置标志 + wakeup，不做 join，用于同步协调。GroupCommitService 的 run() 退出循环后还做了一次 `sleep(10) + swapRequests + doCommit` 的**收尾 flush**，确保停机前到达的请求不丢。

---

## 4. 竞态考古：两个 issue

### 4.1 Issue #5710（2022-12）：CountDownLatch2 的 reset 竞态

来源：https://github.com/apache/rocketmq/issues/5710

当时的实现（commit aa7a442）：

```java
    protected void waitForRunning(long interval) {
        if (hasNotified.compareAndSet(true, false)) {
            this.onWaitEnd();
            return;
        }
        //entry to wait
        waitPoint.reset();
        try {
            waitPoint.await(interval, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { ... }
```

问题：`wakeup()` 里的 `countDown()` 与 worker 里的 `reset()` 之间存在竞态窗口。发起人（xiaoyifang）指出"在多线程环境下执行顺序不同结果不同，最坏情况下 waitForRunning 需要多等一个完整的 interval"，并明确建议：**"A critical section(lock etc.) maybe better involved."（临界区/锁可能更好）**——即建议改用 `Condition` 类方案，因为 CountDownLatch 按 javadoc 设计就不支持 reset。

维护者回复确认了竞态存在但否认会"无限等待"：

> 1. 在 `waitPoint.reset()` 之前执行 `wakeup()` 会导致额外等待一个 interval；
> 2. 在 `waitPoint.reset()` 之后执行 `wakeup()` 会立即唤醒。

提交者随后提了修复 PR（多次 commit "optimize ServiceThread's waitForRunning logic"），但该 issue 最终因 365 天无活动被 stale 关闭，未彻底解决。

### 4.2 Issue #10543（2026）：丢失唤醒被定性为 Bug 并修复

来源：https://github.com/apache/rocketmq/issues/10543
标题：`[Bug] ServiceThread#wakeup() can be lost when it races with waitForRunning()'s waitPoint.reset()`

竞态时序（issue 原文）——线程 A 为 worker（`waitForRunning(1000)`），线程 B 为调用方（`wakeup()`），初始 `hasNotified=false`、latch count=1：

| 步骤 | 线程 A（waitForRunning） | 线程 B（wakeup） | 状态 |
|---|---|---|---|
| 1 | `hasNotified.CAS(true,false)` → false，跳过快速路径 | | hasNotified=false, count=1 |
| 2 | （即将调用 reset()） | `hasNotified.CAS(false,true)` → true；`waitPoint.countDown()` | hasNotified=true, count=0 |
| 3 | `waitPoint.reset()` → count 被恢复为 1（**countDown 被丢弃**） | | hasNotified=true, count=1 |
| 4 | `waitPoint.await(1000)` 阻塞；不会再有 countDown | | 阻塞 ~1000ms |
| 5 | 超时后 finally 里 `hasNotified.set(false)`，`onWaitEnd()` | | **唤醒丢失** |

定性：`hasNotified` 标志为 true 但 latch 许可被 `reset()` 覆盖 → B 已投递的唤醒被吞，worker 白白睡满 interval（默认 1000ms）。对刷盘线程意味着生产请求整体延迟最多一个 interval。

**修复方案**：彻底移除 CountDownLatch2，换成第 1 节的实现——`LockSupport.unpark` 的粘性许可 + deadline 循环重查 `hasNotified`。修复后第 3 步的"覆盖"不再存在：unpark 许可不会因任何"reset"丢失，worker 无论从哪个点醒来都会重查标志。修复版还把 `hasNotified.set(false)` 从 finally 移到等待出口统一执行，并新增 `this.thread = Thread.currentThread()` 的线程发布，修复了 restart 场景下 unpark 打错线程的隐患。

---

## 5. 对 ShardFlusher 设计的借鉴与避坑

### 可借鉴要点

1. **双缓冲队列 + O(1) 交换**（第 2.1–2.3 节）：生产端 `putRequest` 只在短临界区内 add 到 write 队列然后 signal；消费线程被唤醒后 swap 两个队列，随后在**无锁状态**下批量处理 read 队列。ShardFlusher 可照此实现：锁竞争被压缩到两个 O(1) 点，flush（最耗时的部分）期间不持锁，生产端永不因慢 flush 而阻塞。
2. **唤醒去重的粘性许可语义**：RocketMQ 用 `hasNotified` CAS 去重 + LockSupport 粘性许可。ShardFlusher 若用 Condition，等价做法是设一个 `pendingSignal` AtomicBoolean：signal 前 CAS 防重复，worker 消费后复位——即使 signal 稍早于 await（在 Condition 上 signal 释放锁后 worker 尚未 await 是安全的，因为 await 前持锁、signal 前也持锁，通知不会跨过锁屏障丢失），仍应保留标志兜底，并以"循环 + awaitUntil(deadline) 重查谓词"（如 `pendingFlush` 非空）作为最终一致性保证，而不是单次 await 信念式依赖 signal。
3. **停机顺序：先置标志 → 再 signal → 最后 join**（第 3 节）：RocketMQ 的 `stopped=true; wakeup(); join()` 顺序保证 worker 从等待中醒来时必然先看到停止标志。ShardFlusher 的 `shutdown()` 应同样：置 `stopped`（volatile）→ 持锁 `signal` → `join(带超时)`。且 worker 退出循环后要像 GroupCommitService 那样做**收尾 flush**（退出 while 后再 swap + doCommit 一次），保证停机瞬间已到达的请求不丢。
4. **常驻线程 + 统一 deadline 等待**：worker 主循环固定为 `while (!stopped) { 等待(带 deadline); 处理; }`，deadline 覆盖整个等待期（超时醒来后按剩余时间继续等或直接执行定时任务）。这天然支持两类唤醒：有活时 signal 即刻醒，无活时 deadline 到期兜底空转/心跳。GroupCommitService 的 `waitForRunning(10)`（10ms 间隔）即依赖这一语义。
5. **线程引用/所有权管理**：修复版在 park 前发布 `thread = Thread.currentThread()` 并用 `started` CAS 保证 start/shutdown 幂等、可重启。ShardFlusher 若需要在锁外 unpark 或 interrupt，也应让 worker 在进入等待前把自己发布到 volatile 字段，避免向未启动/已替换的线程发信号。

### 要避开的坑

1. **绝不用"可重置的计数器/latch 当通知原语"**：#10543 的教训——`countDown()` + `reset()` 组合在信号与复位竞态时会**吞掉唤醒**（时序见 4.2 表格）。对应到 Condition：不要在持锁区外"预消费/重置"某个计数再进入 await（例如 await 前先 `pendingCount.set(0)` 之类的模式），那会重建同样的竞态窗口。通知状态只能由 worker 在持锁的消费路径上单向消费。
2. **不要把"标志位为 true"当作"许可已投递"的充分条件而无底层粘性兜底**：#5710 中 `hasNotified=true` 但 latch 许可被 reset 覆盖，标志与底层等待器状态脱节。Condition 方案里标志与队列检查必须在**同一把锁、同一次谓词重查**内完成；任何在锁外的标志操作都可能造成标志先行、谓词滞后的脱节。
3. **不要依赖"维护者说不会无限等"就把最坏延迟当可接受**：#5710 从 2022 拖到 2026 才被定性为 bug——中间版本里唤醒丢失只表现为"多等一个 interval"，在压测/生产中极难归因。ShardFlusher 从第一天就应把丢失唤醒视为正确性 bug 而非性能噪声，用确定性复现测试（如 #10543 附带的 ServiceThreadTest 注入阻塞 reset 的思路：人为制造 signal 与 await 入口竞态，断言等待即时返回）锁住行为。
4. **不要让停机 join 无上限**：RocketMQ join 都带 90s 超时（`JOIN_TIME = 90 * 1000`）。ShardFlusher 的 join 也必须带超时并记录耗时，避免卡死的 flush（磁盘 hang）拖死整个停机流程。
5. **不要在 flush 长临界区内持锁接收新请求**：GroupCommitService 的 doCommit（含 1000 次循环 flush + sleep(1)）全程无锁。若 ShardFlusher 在持 Condition 锁的状态下做 flush，生产端 signal 全部排队，等于退化成同步调用——锁必须只在"检查谓词 / 交换队列 / 唤醒"时持有。

---

## 附：来源清单

| 材料 | 出处 |
|---|---|
| ServiceThread 修复版全文 | apache/rocketmq develop 分支，`common/src/main/java/org/apache/rocketmq/common/ServiceThread.java`（raw 摘录于本文第 1 节） |
| GroupCommitService 五段源码 | apache/rocketmq develop 分支，`store/src/main/java/org/apache/rocketmq/store/CommitLog.java` 内部类（摘录于第 2 节） |
| Issue #5710（2022-12，CountDownLatch2 reset 竞态，建议换 Condition） | https://github.com/apache/rocketmq/issues/5710 |
| Issue #10543（2026，丢失唤醒定性为 bug，修复为 LockSupport 方案） | https://github.com/apache/rocketmq/issues/10543 |
| 备用源（未动用，页面可正常访问） | https://mail-archive.com/commits@rocketmq.apache.org/msg69354.html 、https://mail-archive.com/commits@rocketmq.apache.org/msg69524.html |
