# 04: timeline 清理 job（30 天可配）+ 超期兜底协议

**What to build:** 写扩散收件箱获得退场机制（对齐业界：Signal 30 天、Tablestore 同步库 7 天、野火队列上限）。完成后：timeline 存储量随保留期有界；离线超过保留期的客户端补拉时检测到 `delivery_seq` 缺口后，按 `room_member.last_seq_copy` 逐会话兜底拉取，不永久丢消息。

**Blocked by:** 01（软依赖 02 的 `awaitDiffusion` 助手，可并行开工）。

**Status:** ready-for-agent

- [ ] XXL-Job 清理任务：按保留期（30 天，可配置）删除 timeline 过期行
- [ ] 缺口语义：补拉返回的首行 `delivery_seq` 与客户端游标不连续即为缺口（服务端保证返回口径可判定）
- [ ] 兜底拉取：按会话 `last_seq_copy` 与客户端 per-conv seq 的差值逐会话拉缺口（复用既有按 seq 拉历史的读路径）
- [ ] 集成测试：构造清理后缺口场景 → 补拉检出缺口 → 逐会话兜底拉全；清理任务幂等可重跑
