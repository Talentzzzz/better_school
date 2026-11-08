import re, os

def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (p, old[:80])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print("ok", p)

def cut_method(src, signature):
    idx = src.index(signature)
    start = src.rindex('    @Override', 0, idx)
    m = re.search(r'(/\*\*[\s\S]*?\*/\n)(    @Override\n)?' + re.escape(signature), src)
    if m:
        start = m.start(1)
    nxt = re.search(r'\n    (?:@Override|/\*\*|private|public|static|final|// -)', src[idx:])
    end = idx + nxt.start() + 1
    return src[:start] + src[end:], src[start:end]

# ============ IErrandOrderService ============
edit('bs-domain/src/main/java/com/betterschool/domain/errand/service/IErrandOrderService.java', [
("import com.betterschool.domain.errand.model.valobj.ErrandReviewResult;\n", ""),
])

s = open('bs-domain/src/main/java/com/betterschool/domain/errand/service/IErrandOrderService.java', encoding='utf-8').read()
i = s.index("    /** 审核通过（裁决用例 decide 编排）")
# cut the block from that javadoc to the payOrder javadoc
j = s.index("    /** 确认支付（无事务）")
s = s[:i] + s[j:]
k = s.index("    /** 待审超时订单 id 列表")
l = s.index("    @Override", k) if "    @Override" in s[k:] else None
# remove the listPendingReviewTimeoutOrderIds declaration block (javadoc + signature + ;)
m = re.search(r'    /\*\* 待审超时订单 id 列表[\s\S]*?\*/\n    List<String> listPendingReviewTimeoutOrderIds\(\);\n', s)
assert m
s = s[:m.start()] + s[m.end():]
open('bs-domain/src/main/java/com/betterschool/domain/errand/service/IErrandOrderService.java', 'w', encoding='utf-8', newline='').write(s)
print("ok IErrandOrderService")

# ============ ErrandOrderServiceImpl ============
P = 'bs-domain/src/main/java/com/betterschool/domain/errand/service/ErrandOrderServiceImpl.java'
s = open(P, encoding='utf-8').read()
i = s.index("    @Override\n    public void reviewApproveOrder")
j = s.index("    @Override\n    public ErrandPayResult payOrder")
s = s[:i] + s[j:]

m = re.search(r'    @Override\n    public List<String> listPendingReviewTimeoutOrderIds\(\) \{[\s\S]*?\n    \}\n\n', s)
assert m
s = s[:m.start()] + s[m.end():]

rep_pairs = [
("""        // 幂等判定：已支付（PENDING_REVIEW 待审）视为上次支付已提交成功（重试场景），跳过直接返回
        if (orderEntity.getStatus() == ErrandStatusVO.PENDING_REVIEW) {
            return new ErrandPayResult(orderEntity.getTotalReward());
        }""",
"""        // 幂等判定：已支付（PUBLISHED 待接单）视为上次支付已提交成功（重试场景），跳过直接返回
        if (orderEntity.getStatus() == ErrandStatusVO.PUBLISHED) {
            return new ErrandPayResult(orderEntity.getTotalReward());
        }"""),
("""        ErrandTimeLimitVO timeLimitVO = ErrandTimeLimitVO.fromHours(orderEntity.getTimeLimitHours());
        // 先付后审（ADR-0003）：状态迁移跟着钱走——支付事务内转 PENDING_REVIEW（已付款待审），
        // bs-errand-paid outbox 消息只承担"进入人工审核"通知，不承载状态迁移；
        // 超时扫描按 deadline 判定（待审期先到点会被 handleOrderTimeout 按状态幂等忽略）
        errandRepository.payWithPaidMessage(orderId, userId, timeLimitVO.getHours());""",
"""        ErrandTimeLimitVO timeLimitVO = ErrandTimeLimitVO.fromHours(orderEntity.getTimeLimitHours());
        // 状态迁移跟着钱走：支付事务内转 PUBLISHED（冻结悬赏、即对外可见进入大厅），
        // bs-errand-paid outbox 消息只承担"支付成功"通知，不承载状态迁移
        errandRepository.payWithPaidMessage(orderId, userId, timeLimitVO.getHours());"""),
("""        // 软删单对外与作者均不可见；草稿与待审单仅作者本人可见（审核员走 moderation 审核队列详情，其他人 404）
        if (orderEntity.getStatus() == ErrandStatusVO.DELETED
                || ((orderEntity.getStatus() == ErrandStatusVO.DRAFT
                || orderEntity.getStatus() == ErrandStatusVO.PENDING_REVIEW)
                && !orderEntity.isOwner(currentUserId))) {""",
"""        // 软删单对外与作者均不可见；草稿单仅作者本人可见
        if (orderEntity.getStatus() == ErrandStatusVO.DELETED
                || (orderEntity.getStatus() == ErrandStatusVO.DRAFT
                && !orderEntity.isOwner(currentUserId))) {"""),
("""        if (orderEntity.getStatus() != ErrandStatusVO.PENDING_PAYMENT
                && orderEntity.getStatus() != ErrandStatusVO.PENDING_REVIEW
                && orderEntity.getStatus() != ErrandStatusVO.PUBLISHED""",
"""        if (orderEntity.getStatus() != ErrandStatusVO.PENDING_PAYMENT
                && orderEntity.getStatus() != ErrandStatusVO.PUBLISHED"""),
("        // 待支付未冻结无需退款；待审/已发布/锁单中/已确认需解冻退回",
 "        // 待支付未冻结无需退款；已发布/锁单中/已确认需解冻退回"),
]
for old, new in rep_pairs:
    assert old in s, old[:60]
    s = s.replace(old, new)
s = s.replace("    private static final int REVIEW_REMINDER_HOURS = 24;\n", "")
open(P, 'w', encoding='utf-8', newline='').write(s)
print("ok ErrandOrderServiceImpl")

# ============ IErrandRepository ============
p = 'bs-domain/src/main/java/com/betterschool/domain/errand/adapter/repository/IErrandRepository.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'    /\*\* 待审超时 id 列表[\s\S]*?boolean reviewRejectById\(String orderId, String operatorId, String reason\);

', s)
assert m, "iface block"
s = s[:m.start()] + s[m.end():]
s = s.replace("    /** 支付转待审 + 落 bs-errand-paid 事件（条件 UPDATE 0 行抛 BUSINESS_ERROR） */
    void payWithPaidMessage(String orderId, String userId, int timeLimitHours);",
              "    /** 支付转 PUBLISHED（冻结悬赏、进入大厅）+ feed 索引行诞生 + 落 bs-errand-paid 事件（条件 UPDATE 0 行抛 BUSINESS_ERROR） */
    void payWithPaidMessage(String orderId, String userId, int timeLimitHours);")
open(p, 'w', encoding='utf-8', newline='').write(s)
print("ok IErrandRepository")

# ============ ErrandRepository ============
P = 'bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/repository/errand/ErrandRepository.java'
s = open(P, encoding='utf-8').read()
for sig in [
    'public List<String> listPendingReviewOrderIds(LocalDateTime before, int limit)',
    'public boolean reviewApproveById(String orderId, String operatorId)',
    'public boolean reviewRejectById(String orderId, String operatorId, String reason)',
]:
    s, removed = cut_method(s, sig)
    print("dropped:", sig.strip()[:60], f"({len(removed)} chars)")

old = """            saveErrandPaidMessage(orderId);
            // 入池点（spec §5.1）：支付成功进待审，工单开单与状态迁移同事务；
            // entered_at 由开单 SQL NOW() 落库（≈支付成功时刻，同事务同秒），轮次恒 1
            reviewTaskPoolStore.enter(ReviewTargetTypeVO.ERRAND, orderId, 1, userId);
            return null;"""
assert old in s
s = s.replace(old, """            saveErrandPaidMessage(orderId);
            // 支付即进入大厅：feed 索引行诞生（同事务唯一诞生点），feed_time = 进入大厅时刻，price = 悬赏快照
            errandFeedIndexDao.upsertFeedIndexOnApprove(orderId);
            return null;""")

s = s.replace("import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;\n", "")
s = s.replace("import com.betterschool.infrastructure.adapter.repository.moderation.ReviewTaskPoolStore;\n", "")
s = s.replace("    private final ReviewTaskPoolStore reviewTaskPoolStore;\n", "")
open(P, 'w', encoding='utf-8', newline='').write(s)
print("ok ErrandRepository")

os.remove('bs-domain/src/main/java/com/betterschool/domain/errand/model/valobj/ErrandReviewResult.java')
print("valobj removed")
