/**
 * 合并引擎（专属虚拟线程 leader 模型）：与 {@code com.betterschool.infrastructure.merge}
 * 并存的同语义重写，2026-11-24 按
 * {@code backend/docs/architecture/merge-virtual-leader-engine-spec.md} 落地实现。
 *
 * <p>一句话概括：每个组创建时经 claim-CAS 恰好 spawn 一根专属虚拟 leader，以 supervisor 形态
 * （while + catch(Throwable) + 整组回填 FAILED）独占「收集 + 落库」，只在组被置死并从 map
 * 摘除后退出（退出谓词）；旧引擎的运行时领导权协议（tryPromote / yieldLeadership / 点名接班 /
 * 交接探测）在本模型下整体删除——leader 不退位，就不需要接班。
 *
 * <p>对外复用旧包公开类型（{@code MergeConfig} / {@code MergeStrategy} / {@code MergeResult}），
 * commit / awaitIdle / pending / close 语义不变，调用方切换零改动（只换引擎类名）；
 * <b>调用方暂不切换</b>，替换决策在新引擎测试充分后另立票。
 *
 * <p>并发不变量（I1 不丢件恰一终态、I2 同 key 单飞、I3 锁不跨 DB、I4 停机三步、I5 背压有界）
 * 已在新结构下重新论证，见 {@code VirtualMergeEngine} 类注释；JDK 21 JDBC pinning 记录为
 * 性能风险（JEP 491 于 JDK 24 解决），进程级 OOM 归 ops 的 {@code ExitOnOutOfMemoryError}。
 */
package com.betterschool.infrastructure.mergevirtual;
