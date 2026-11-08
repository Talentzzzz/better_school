import re, os

def cut_method(src, signature):
    idx = src.index(signature)
    start = src.rindex('    @Override', 0, idx)
    m = re.search(r'(/\*\*[\s\S]*?\*/\n)(    @Override\n)?' + re.escape(signature), src)
    if m:
        start = m.start(1)
    nxt = re.search(r'\n    (?:@Override|/\*\*|private|public|static|final|// -)', src[idx:])
    end = idx + nxt.start() + 1
    return src[:start] + src[end:], src[start:end]

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
