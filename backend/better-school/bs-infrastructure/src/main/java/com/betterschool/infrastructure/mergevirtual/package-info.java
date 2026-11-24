/**
 * 合并引擎（专属虚拟线程 leader 模型）：与 {@link com.betterschool.infrastructure.merge} 并存的
 * 重写候选，尚未实现——先立规格后动工。
 *
 * <p>设计来源与全部实现决策见
 * {@code backend/docs/architecture/merge-virtual-leader-engine-spec.md}（状态 ready-for-agent）。
 * 一句话概括：每个组创建时经 claim-CAS 恰好 spawn 一根专属虚拟 leader，以 supervisor 形态
 * （while + catch(Throwable) + 整组回填 FAILED）独占「收集 + 落库」，只在组摘除后退出；
 * 旧引擎的运行时领导权协议（tryPromote / yieldLeadership / 点名接班 / 交接探测）在本模型下
 * 整体删除——leader 不退位，就不需要接班。
 *
 * <p>本包当前仅含本骨架文件；实现落位后包内类的并发不变量（I1 不丢件恰一终态、
 * I2 同 key 单飞、I3 锁不跨 DB、I4 停机三步、I5 背压有界）须在新结构下重新论证。
 */
package com.betterschool.infrastructure.mergevirtual;
