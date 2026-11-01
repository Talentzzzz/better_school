# -*- coding: utf-8 -*-
"""票 17 冒烟：项 1（发帖/评论先审后发：敏感词初筛 → 人工） + 项 8（后台审核）。

专属号段：137000171xx（见 README.md）
- author  = 13700017101（发帖/评论人）
- admin   = 13700017102（提权 ADMIN 后重登，管理员）
- commenter = 13700017103（评论人，用来验证"旁人列表看不到"）

跑法：
    cd C:\\Users\\a1368\\Desktop\\better_school
    python .scratch\\project-rework\\verify-17\\smoke_01_08_review.py

设计口径：
- 只依赖 smoke_lib.py（不修改它）+ 标准库。
- 每条断言打一行 PASS/FAIL；失败不中断，最后汇总并 exit 1。
- 敏感词取自 backend/better-school/sql/schema.sql 的种子（代考/代课/作弊/赌博/枪手/办证/刷单/跑分），
  按键 `包含` 匹配（KeywordScreenServiceImpl 内存词表 contains）。
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from smoke_lib import Harness, BS_BASE, BS_LOG, log_offset, read_since  # noqa: E402

# ---------------- 常量（对齐 schema.sql 种子与集成测试口径） ----------------
AUTHOR_PHONE = "13700017101"
ADMIN_PHONE = "13700017102"
COMMENTER_PHONE = "13700017103"

SEED_WORDS = ["代考", "代课", "作弊", "赌博", "枪手", "办证", "刷单", "跑分"]
POST_DIRTY = "有偿代考，私聊"           # 命中种子词 '代考'（正文）
POST_DIRTY_TITLE = "代课信息"           # 命中种子词 '代课'（标题）
COMMENT_DIRTY = "这个可以赌博换钱"      # 命中种子词 '赌博'（评论正文）
CLEAN_MARK = "SMOKE17CLEAN"             # 干净正文标记（可重跑定位）
RUN_ID = "R%s%s" % (time.strftime("%m%d%H%M%S"), str(int(time.time() * 1000) % 1000))  # 本次运行唯一标记

REJECT_SUPPLEMENT = "标题与内容不符"    # 拒绝补充说明（项 8）
REJECT_REASON_CODE = "OTHER"
EXPECT_REJECT_REASON = "其他：" + REJECT_SUPPLEMENT

CHECK_PUBLIC = "/api/community/posts?page=1&pageSize=1"

# ---------------- 断言登记 ----------------
CHECKS = []


def check(name, cond, detail=""):
    ok = bool(cond)
    CHECKS.append((name, ok, detail))
    print("%s | %s%s" % ("PASS" if ok else "FAIL", name, ((" — " + str(detail)) if detail else "")))
    return ok


def rows_json(h, db, sql):
    """查库并把行转成 [{列: 值}]。

    列名**必须从本查询的 SELECT 列表取**（不能拿表的 ORDINAL_POSITION 去 zip：
    查询常只选子集且顺序不同，错位会把 action 的值贴到 id 上，证据就假了）。
    仅支持本脚本用到的写法：逗号分隔的裸列名（无别名/无表达式）。
    """
    head = sql.split("FROM", 1)[0].strip()
    assert head.upper().startswith("SELECT"), "rows_json 只支持以 SELECT 开头的查询：%r" % sql
    cols = [c.strip().strip("`") for c in head[len("SELECT"):].split(",")]
    assert "*" not in cols, "rows_json 需要显式列名（证据要可读）：%r" % sql
    return [{cols[i]: row[i] for i in range(len(row))} for row in h.sql(db, sql)]


def sms_code_of(h, phone, offset, timeout=45.0):
    """从单体日志精确抓「[SMS-mock] 向 {phone} 发送验证码：{code}」。

    自带实现（不用 smoke_lib.sms_code）：后者的正则 `{phone}[^\\n]*?(\\d{6})` 不锚定，
    会把行内其它 6 位数字（时间戳/毫秒）或其它号段的行当成本次验证码；
    这里用带 `发送验证码：` 锚点的精确模式，并等到目标行走完整为止。
    """
    pattern = re.compile(r"\[SMS-mock\] 向 " + re.escape(phone) + r" 发送验证码：(\d{6})")
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        text = read_since(BS_LOG, offset)
        found = pattern.findall(text)
        if found:
            return found[-1]
        last = text[-200:]
        time.sleep(0.3)
    raise AssertionError("日志里抓不到 %s 的验证码；偏移后尾部内容：%r" % (phone, last))


def login_with_code(h, phone, nickname=None):
    """自带登录（注册优先）：返回 (token, userId)。"""
    off = log_offset(BS_LOG)
    h.call("POST", "/api/user/send-code", {"phone": phone})
    code = sms_code_of(h, phone, off)
    if nickname is not None:
        st, r = h.call("POST", "/api/user/register", {"phone": phone, "code": code, "nickname": nickname})
        if isinstance(r, dict) and r.get("code") == "200":
            return r["data"]["token"], r["data"]["userInfo"]["userId"]
        # 已注册过 → 换码登录
        off = log_offset(BS_LOG)
        h.call("POST", "/api/user/send-code", {"phone": phone})
        code = sms_code_of(h, phone, off)
    st, r = h.call("POST", "/api/user/login", {"phone": phone, "code": code})
    assert isinstance(r, dict) and r.get("code") == "200", "登录失败 %s：%s" % (phone, r)
    return r["data"]["token"], r["data"]["userInfo"]["userId"]


def promote_admin(h, phone):
    """提权 ADMIN 后重登（角色写在 JWT 里，必须换新 token）。

    不用 smoke_lib.promote_admin：那个方法只做了 UPDATE，且列名曾是错的；
    这里与集成测试 adminSession() 同口径：注册/登录取 userId → UPDATE role → 换新 token。
    """
    _, user_id = login_with_code(h, phone, nickname="冒烟管理员")
    h.sql("better_school", "UPDATE bs_user SET role='ADMIN' WHERE phone='%s'" % phone)
    tok, uid = login_with_code(h, phone)
    assert str(uid) == str(user_id), "提权后重登 userId 变化：%s → %s" % (user_id, uid)
    return tok, uid


def feed_ids(h):
    data = h.ok("GET", "/api/community/posts?page=1&pageSize=50")
    return [int(item["postId"]) for item in data["list"]]


def queue_items(h, token, target_type, page_size=50):
    data = h.ok("GET", "/api/moderation/queue?targetType=%s&page=1&pageSize=%d" % (target_type, page_size),
                token=token)
    return data["list"], int(data["total"])


def comment_ids(h, post_id, token=None):
    data = h.ok("GET", "/api/community/posts/%d/comments?page=1&pageSize=10" % post_id, token=token)
    return [int(c["commentId"]) for c in data["list"]], int(data["total"])


# ---------------- 栈就绪轮询（每 30s，最多 25 分钟） ----------------
def wait_for_stack():
    deadline = time.time() + 25 * 60
    attempt = 0
    while True:
        attempt += 1
        try:
            req = urllib.request.Request(BS_BASE + CHECK_PUBLIC)
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 200:
                    print("PASS | 栈就绪：%s → HTTP 200（第 %d 次探测）" % (CHECK_PUBLIC, attempt))
                    return True
        except urllib.error.HTTPError as e:
            print("  ... 栈未就绪（第 %d 次探测）：HTTP %s" % (attempt, e.code))
        except Exception as e:  # noqa: BLE001
            print("  ... 栈未就绪（第 %d 次探测）：%s" % (attempt, e))
        if time.time() >= deadline:
            print("FAIL | 栈 25 分钟未就绪，放弃（%s）" % BS_BASE)
            return False
        time.sleep(30)


# ---------------- 项 1：先审后发（敏感词初筛 → 人工） ----------------
def item1(h, author, author_id, commenter):
    author_tok = author
    print("\n=== 项 1：发帖 / 评论先审后发（敏感词初筛 → 人工） ===")

    # --- 0) 前置：种子敏感词已在库里（判定依据是 schema.sql 种子，不是猜的） ---
    words = [r[0] for r in h.sql("better_school", "SELECT word FROM sensitive_word WHERE enabled = 1 ORDER BY id")]
    h.evidence("01-sensitive-words", {"seed_from_schema_sql": SEED_WORDS, "db_enabled_words": words})
    check("1.0 敏感词库已灌种子（enabled=1）", set(SEED_WORDS).issubset(set(words)),
          "库中 %d 条：%s" % (len(words), words))

    # --- 1) 发帖命中敏感词（正文）：同步初筛 → 报错 + 不落库 ---
    # 脏正文也带本次运行唯一标记：这样"没落库"的判据是"库里没有任何本次运行标记的行"，
    # 既不会被可重跑的历史干净帖污染，也不会漏掉"部分落库"的情形。
    dirty_content = POST_DIRTY + " " + CLEAN_MARK + " " + RUN_ID
    st, resp = h.call("POST", "/api/community/posts",
                      {"title": "求助帖", "content": dirty_content, "category": "DAILY"}, token=author_tok)
    h.evidence("01-dirty-post-response", {
        "http_status": st, "request": {"title": "求助帖", "content": dirty_content, "category": "DAILY"},
        "response": resp, "hit_word": "代考"})
    check("1.1a 正文命中敏感词：发帖被同步初筛拒绝（非成功码）",
          st == 200 and isinstance(resp, dict) and resp.get("code") not in ("200", "0000"),
          "HTTP %s code=%s" % (st, resp.get("code") if isinstance(resp, dict) else resp))
    check("1.1b 拒绝提示引导修改（'内容包含违规词汇，请修改后重试'）",
          isinstance(resp, dict) and "内容包含违规词汇，请修改后重试" in str(resp.get("info", "")),
          resp.get("info") if isinstance(resp, dict) else resp)
    dirty_rows = h.sql("better_school",
                       "SELECT post_id, state FROM post WHERE content LIKE '%%%s%%'" % RUN_ID)
    h.evidence("01-dirty-post-db", {"query": "post WHERE content LIKE '%%%s%%'" % RUN_ID,
                                    "run_id": RUN_ID, "rows": dirty_rows})
    check("1.1c 命中敏感词的帖子不落库（本次运行标记 0 行）", len(dirty_rows) == 0, "查到 %d 行" % len(dirty_rows))

    # --- 2) 发帖命中敏感词（标题）：标题同桌初筛 ---
    st2, resp2 = h.call("POST", "/api/community/posts",
                        {"title": POST_DIRTY_TITLE, "content": "干净正文 " + RUN_ID, "category": "DAILY"},
                        token=author_tok)
    h.evidence("01-dirty-title-response", {"http_status": st2, "response": resp2, "hit_word": "代课",
                                          "run_id": RUN_ID})
    check("1.2 标题命中敏感词同样被拒且不落库",
          st2 == 200 and isinstance(resp2, dict) and resp2.get("code") not in ("200", "0000")
          and len(h.sql("better_school", "SELECT post_id FROM post WHERE content LIKE '%%%s%%'" % RUN_ID)) == 0,
          "HTTP %s code=%s，本次运行标记落库 %d 行"
          % (st2, resp2.get("code") if isinstance(resp2, dict) else resp2,
             len(h.sql("better_school", "SELECT post_id FROM post WHERE content LIKE '%%%s%%'" % RUN_ID))))

    # --- 3) 干净帖：落库即 PENDING_REVIEW + content_review(MANUAL) + 旁人不可见 ---
    clean_content = "正常帖子内容，等待人工审核 " + CLEAN_MARK + " " + RUN_ID
    st3, resp3 = h.call("POST", "/api/community/posts",
                        {"title": "冒烟干净帖", "content": clean_content, "category": "DAILY"}, token=author_tok)
    h.evidence("01-clean-post-response", {"http_status": st3,
                                          "request": {"title": "冒烟干净帖", "content": clean_content, "category": "DAILY"},
                                          "response": resp3})
    if not (st3 == 200 and isinstance(resp3, dict) and resp3.get("code") == "200"):
        check("1.3 干净帖发布成功", False, "HTTP %s / %s" % (st3, resp3))
        return None
    post_id = int(resp3["data"])
    check("1.3 干净帖发布成功", True, "postId=%s" % post_id)

    post_state = h.one("better_school", "SELECT state FROM post WHERE post_id = %s" % post_id)
    h.evidence("01-clean-post-db", {"post": rows_json(h, "better_school",
             "SELECT post_id, user_id, title, content, state, review_round, review_entered_at, create_time "
             "FROM post WHERE post_id = %s" % post_id)})
    check("1.4 干净帖落库即 state=PENDING_REVIEW", post_state == "PENDING_REVIEW", "state=%s" % post_state)

    reviews0 = rows_json(h, "better_school",
                         "SELECT target_type, target_id, review_round, action, source, operator_id, reason "
                         "FROM content_review WHERE target_type='POST' AND target_id=%s" % post_id)
    h.evidence("01-clean-post-review-rows", {"post_id": post_id, "content_review_rows": reviews0})
    check("1.5 content_review 尚无裁决行（待审=只挂队列，裁决行在项 8 落）", len(reviews0) == 0,
          "%d 行" % len(reviews0))

    # 旁人 & 访客不可见（列表 + 详情）
    feed = feed_ids(h)
    other_feed = h.ok("GET", "/api/community/posts?page=1&pageSize=50", token=commenter)
    other_ids = [int(i["postId"]) for i in other_feed["list"]]
    st_d, det_other = h.call("GET", "/api/community/posts/%d" % post_id, token=commenter)
    st_g, det_guest = h.call("GET", "/api/community/posts/%d" % post_id)
    h.evidence("01-pending-not-visible", {
        "guest_feed_contains": post_id in feed, "other_user_feed_contains": post_id in other_ids,
        "other_detail": {"http_status": st_d, "response": det_other},
        "guest_detail": {"http_status": st_g, "response": det_guest}})
    check("1.6a 待审帖不出现在公共 feed（访客）", post_id not in feed, "feed 共 %d 条" % len(feed))
    check("1.6b 待审帖不出现在旁人 feed", post_id not in other_ids, "旁人 feed 共 %d 条" % len(other_ids))
    check("1.6c 旁人看待审帖详情被拒（code=404）",
          isinstance(det_other, dict) and det_other.get("code") == "404",
          "code=%s" % (det_other.get("code") if isinstance(det_other, dict) else det_other))
    check("1.6d 访客看待审帖详情被拒（code=404）",
          isinstance(det_guest, dict) and det_guest.get("code") == "404",
          "code=%s" % (det_guest.get("code") if isinstance(det_guest, dict) else det_guest))

    # 作者自己可见（"我的"列表）
    mine = h.ok("GET", "/api/community/posts/mine?page=1&pageSize=10", token=author_tok)
    mine_ids = [int(i["postId"]) for i in mine["list"]]
    check("1.7 作者在自己的「我的」列表可见待审帖", post_id in mine_ids, "mine 共 %d 条" % len(mine_ids))

    # --- 4) 评论初筛在 item1_comments() 里跑：评论只允许发在 NORMAL 帖上，
    #        所以要先由项 8 把这个帖子人工通过（见 main 的编排顺序）。
    return post_id


# ---------------- 项 1（续）：评论初审 —— 必须在帖子人工通过之后 ----------------


# ---------------- 项 1（续）：评论初审 —— 必须在帖子人工通过之后 ----------------
def item1_comments(h, author_tok, author_id, commenter_tok, commenter_id, post_id):
    """评论初筛 + 先审后发。

    前置口径：评论只能发在 state=NORMAL 的帖子上（CommentServiceImpl.getNormalPost
    对非 NORMAL 直接 404「帖子不存在」），所以评论用例必须在项 8 通过该帖之后跑。
    """
    print("\n=== 项 1（续）：评论先审后发（帖子已人工通过，postId=%d） ===" % post_id)

    # 前置确认：帖子已 NORMAL，评论接口是可用的
    pre_state = h.one("better_school", "SELECT state FROM post WHERE post_id=%s" % post_id)
    check("1.C0 前置：目标帖已 NORMAL（评论接口对非 NORMAL 帖 404）", pre_state == "NORMAL", "state=%s" % pre_state)

    # --- 评论命中敏感词：同步初筛 → 报错 + 不落库 ---
    comment_dirty = COMMENT_DIRTY + " " + CLEAN_MARK + " " + RUN_ID
    before_rows = h.count("better_school", "SELECT COUNT(*) FROM post_comment WHERE post_id=%s" % post_id)
    st4, resp4 = h.call("POST", "/api/community/posts/%d/comments" % post_id,
                        {"content": comment_dirty}, token=commenter_tok)
    h.evidence("01-dirty-comment-response", {"http_status": st4, "post_id": post_id,
                                             "request": {"content": comment_dirty}, "response": resp4,
                                             "hit_word": "赌博", "run_id": RUN_ID})
    check("1.8a 评论命中敏感词：被同步初筛拒绝（非成功码）",
          st4 == 200 and isinstance(resp4, dict) and resp4.get("code") not in ("200", "0000"),
          "HTTP %s code=%s" % (st4, resp4.get("code") if isinstance(resp4, dict) else resp4))
    check("1.8b 拒绝提示引导修改（'内容包含违规词汇，请修改后重试'）",
          isinstance(resp4, dict) and "内容包含违规词汇，请修改后重试" in str(resp4.get("info", "")),
          resp4.get("info") if isinstance(resp4, dict) else resp4)
    dirty_crows = h.sql("better_school",
                        "SELECT comment_id FROM post_comment WHERE content LIKE '%%%s%%'" % RUN_ID)
    after_rows = h.count("better_school", "SELECT COUNT(*) FROM post_comment WHERE post_id=%s" % post_id)
    h.evidence("01-dirty-comment-db", {"post_id": post_id, "run_id": RUN_ID,
                                       "comment_rows_before": before_rows, "comment_rows_after": after_rows,
                                       "dirty_marker_rows": dirty_crows,
                                       "all_comments_of_post": rows_json(h, "better_school",
                                           "SELECT comment_id, user_id, content, state FROM post_comment "
                                           "WHERE post_id=%s" % post_id)})
    check("1.8c 命中敏感词的评论不落库（本次运行标记 0 行且该帖评论数不变）",
          len(dirty_crows) == 0 and after_rows == before_rows,
          "before=%d after=%d marker=%d" % (before_rows, after_rows, len(dirty_crows)))

    # --- 干净评论：落库即 PENDING_REVIEW，旁人看不到，作者可见 ---
    clean_comment = "干净评论内容 " + CLEAN_MARK + " " + RUN_ID
    st5, resp5 = h.call("POST", "/api/community/posts/%d/comments" % post_id,
                        {"content": clean_comment}, token=commenter_tok)
    h.evidence("01-clean-comment-response", {"http_status": st5, "post_id": post_id,
                                             "request": {"content": clean_comment}, "response": resp5})
    if not (st5 == 200 and isinstance(resp5, dict) and resp5.get("code") == "200"):
        check("1.9 干净评论发布成功", False, "HTTP %s / %s" % (st5, resp5))
        return None
    comment_id = int(resp5["data"]["commentId"])
    check("1.9 干净评论发布成功", True, "commentId=%s" % comment_id)

    # 第二条干净评论：给项 8 当"驳回"目标（第一条留给项 8 通过）
    clean_comment2 = "干净评论内容二，待驳回 " + CLEAN_MARK + " " + RUN_ID
    st5b, resp5b = h.call("POST", "/api/community/posts/%d/comments" % post_id,
                          {"content": clean_comment2}, token=commenter_tok)
    h.evidence("01-clean-comment2-response", {"http_status": st5b, "post_id": post_id,
                                              "request": {"content": clean_comment2}, "response": resp5b})
    if not (st5b == 200 and isinstance(resp5b, dict) and resp5b.get("code") == "200"):
        check("1.9b 第二条干净评论发布成功（项 8 驳回目标）", False, "HTTP %s / %s" % (st5b, resp5b))
        return {"approve_comment_id": comment_id, "reject_comment_id": None}
    reject_comment_id = int(resp5b["data"]["commentId"])
    check("1.9b 第二条干净评论发布成功（项 8 驳回目标）", True, "commentId=%s" % reject_comment_id)

    cstate = h.one("better_school", "SELECT state FROM post_comment WHERE comment_id=%s" % comment_id)
    h.evidence("01-clean-comment-db", {"post_comment": rows_json(h, "better_school",
             "SELECT comment_id, post_id, user_id, content, state, root_id, parent_id, create_time "
             "FROM post_comment WHERE comment_id=%s" % comment_id)})
    check("1.10 干净评论落库即 state=PENDING_REVIEW", cstate == "PENDING_REVIEW", "state=%s" % cstate)

    ids_author, _ = comment_ids(h, post_id, token=commenter_tok)
    ids_other, total_other = comment_ids(h, post_id, token=author_tok)
    _, total_guest = comment_ids(h, post_id)
    h.evidence("01-comment-visibility", {"post_id": post_id, "comment_id": comment_id,
                                         "comment_author_view_ids": ids_author,
                                         "other_user_view": {"ids": ids_other, "total": total_other},
                                         "guest_view_total": total_guest})
    check("1.11a 评论作者本人可见自己的待审评论", comment_id in ids_author, "作者视角 %s" % ids_author)
    check("1.11b 旁人看不到待审评论（total=0 且列表空）",
          total_other == 0 and not ids_other, "旁人 total=%d ids=%s" % (total_other, ids_other))
    check("1.11c 访客看不到待审评论（total=0）", total_guest == 0, "访客 total=%d" % total_guest)
    return {"approve_comment_id": comment_id, "reject_comment_id": reject_comment_id}


# ---------------- 项 8：后台审核 ----------------
def item8(h, admin_tok, admin_id, author_tok, author_id, approved_post_id):
    print("\n=== 项 8：后台审核（拉队列 / 通过 / 驳回） ===")

    # --- 0) 前置：另发一帖作为"驳回"目标 ---
    reject_content = "待驳回的帖子内容，等待人工审核 " + CLEAN_MARK + " " + RUN_ID
    resp = h.ok("POST", "/api/community/posts",
                {"title": "冒烟驳回帖", "content": reject_content, "category": "DAILY"}, token=author_tok)
    reject_post_id = int(resp)
    h.evidence("08-fixtures", {"approve_target_post_id": approved_post_id,
                               "reject_target_post_id": reject_post_id, "admin_id": admin_id,
                               "author_id": author_id})

    # --- 1) ADMIN 拉队列：两条都在里面 ---
    items, total = queue_items(h, admin_tok, "POST")
    queue_ids = [int(i["targetId"]) for i in items]
    h.evidence("08-queue-before", {"targetType": "POST", "total": total, "items": items})
    check("8.1a ADMIN 可拉审核队列（POST）", total >= 2, "total=%d" % total)
    check("8.1b 队列含待通过与待驳回两条目标",
          approved_post_id in queue_ids and reject_post_id in queue_ids, "队列 %s" % queue_ids)

    detail = h.ok("GET", "/api/moderation/queue/POST/%d" % approved_post_id, token=admin_tok)
    h.evidence("08-queue-detail", {"targetType": "POST", "targetId": approved_post_id, "detail": detail})
    check("8.1c 队列详情带出 authorId 与正文字段",
          int(detail.get("authorId", -1)) == author_id and bool(detail.get("content")),
          "authorId=%s" % detail.get("authorId"))

    # --- 2) 通过 approved_post_id ---
    st, resp = h.call("POST", "/api/admin/reviews/decide",
                      {"targetType": "POST", "targetId": approved_post_id, "approve": True}, token=admin_tok)
    h.evidence("08-approve-response", {"http_status": st,
                                       "request": {"targetType": "POST", "targetId": approved_post_id, "approve": True},
                                       "response": resp})
    check("8.2 API 通过返回成功", st == 200 and isinstance(resp, dict) and resp.get("code") in ("200", "0000"),
          "HTTP %s code=%s" % (st, resp.get("code") if isinstance(resp, dict) else resp))

    state = h.one("better_school", "SELECT state FROM post WHERE post_id=%s" % approved_post_id)
    check("8.3a 通过后目标状态转 NORMAL", state == "NORMAL", "state=%s" % state)

    feed_after = feed_ids(h)
    st_pub, det_pub = h.call("GET", "/api/community/posts/%d" % approved_post_id)
    h.evidence("08-approve-db-and-public", {
        "post": rows_json(h, "better_school", "SELECT post_id, state, review_round, update_time FROM post "
                                              "WHERE post_id=%s" % approved_post_id),
        "content_review": rows_json(h, "better_school",
            "SELECT target_type, target_id, review_round, action, source, operator_id, reason, create_time "
            "FROM content_review WHERE target_type='POST' AND target_id=%s" % approved_post_id),
        "notification": rows_json(h, "better_school",
            "SELECT user_id, scope, type, title, content, ref_type, ref_id, create_time FROM notification "
            "WHERE user_id=%s AND type='AUDIT_RESULT' AND ref_id=%s" % (author_id, approved_post_id)),
        "feed_contains": approved_post_id in feed_after,
        "guest_detail": {"http_status": st_pub, "code": det_pub.get("code") if isinstance(det_pub, dict) else det_pub}})
    check("8.3b 通过后出现在公共 feed（访客可见）", approved_post_id in feed_after, "feed 共 %d 条" % len(feed_after))
    check("8.3c 通过后访客可打开详情",
          isinstance(det_pub, dict) and det_pub.get("code") == "200", "code=%s" % det_pub.get("code"))

    ap_row = rows_json(h, "better_school",
                       "SELECT action, source, operator_id, review_round FROM content_review "
                       "WHERE target_type='POST' AND target_id=%s AND action='APPROVE'" % approved_post_id)
    check("8.4a content_review 落 APPROVE 裁决行（恰 1 行）", len(ap_row) == 1, "%d 行" % len(ap_row))
    check("8.4b 裁决行 source=MANUAL 且 operator_id=ADMIN 本人",
          len(ap_row) == 1 and ap_row[0]["source"] == "MANUAL" and str(ap_row[0]["operator_id"]) == str(admin_id),
          ap_row[0] if ap_row else None)

    ap_notice = h.sql("better_school",
                      "SELECT type, content FROM notification WHERE user_id=%s AND type='AUDIT_RESULT' "
                      "AND ref_id=%s" % (author_id, approved_post_id))
    check("8.5a 作者收到 AUDIT_RESULT 通知行", len(ap_notice) >= 1, "%d 行" % len(ap_notice))
    check("8.5b 通过通知内容含'通过审核'", any("通过审核" in r[1] for r in ap_notice), ap_notice)

    items_after, _ = queue_items(h, admin_tok, "POST")
    after_ids = [int(i["targetId"]) for i in items_after]
    check("8.6 已通过目标移出审核队列", approved_post_id not in after_ids, "队列 %s" % after_ids)

    # --- 3) 驳回 reject_post_id（带理由） ---
    st_r, resp_r = h.call("POST", "/api/admin/reviews/decide",
                          {"targetType": "POST", "targetId": reject_post_id, "approve": False,
                           "reasonCode": REJECT_REASON_CODE, "supplement": REJECT_SUPPLEMENT}, token=admin_tok)
    h.evidence("08-reject-response", {"http_status": st_r,
        "request": {"targetType": "POST", "targetId": reject_post_id, "approve": False,
                    "reasonCode": REJECT_REASON_CODE, "supplement": REJECT_SUPPLEMENT},
        "response": resp_r})
    check("8.7 API 驳回返回成功", st_r == 200 and isinstance(resp_r, dict) and resp_r.get("code") in ("200", "0000"),
          "HTTP %s code=%s" % (st_r, resp_r.get("code") if isinstance(resp_r, dict) else resp_r))

    rstate = h.one("better_school", "SELECT state FROM post WHERE post_id=%s" % reject_post_id)
    check("8.8a 驳回后目标状态转 REJECTED", rstate == "REJECTED", "state=%s" % rstate)

    feed_r = feed_ids(h)
    st_rd, det_r_other = h.call("GET", "/api/community/posts/%d" % reject_post_id, token=admin_tok)
    st_ra, det_r_author = h.call("GET", "/api/community/posts/%d" % reject_post_id, token=author_tok)
    h.evidence("08-reject-db-and-visibility", {
        "post": rows_json(h, "better_school", "SELECT post_id, state FROM post WHERE post_id=%s" % reject_post_id),
        "content_review": rows_json(h, "better_school",
            "SELECT target_type, target_id, review_round, action, source, operator_id, reason, reason_code, create_time "
            "FROM content_review WHERE target_type='POST' AND target_id=%s" % reject_post_id),
        "notification": rows_json(h, "better_school",
            "SELECT user_id, scope, type, title, content, ref_type, ref_id, create_time FROM notification "
            "WHERE user_id=%s AND type='AUDIT_RESULT' AND ref_id=%s" % (author_id, reject_post_id)),
        "feed_contains": reject_post_id in feed_r,
        "author_detail": {"http_status": st_ra, "code": det_r_author.get("code") if isinstance(det_r_author, dict) else det_r_author,
                          "rejectReason": det_r_author.get("data", {}).get("rejectReason") if isinstance(det_r_author, dict) else None,
                          "state": det_r_author.get("data", {}).get("state") if isinstance(det_r_author, dict) else None},
        "other_detail": {"http_status": st_rd, "code": det_r_other.get("code") if isinstance(det_r_other, dict) else det_r_other}})
    check("8.8b 被拒帖不出现在公共 feed", reject_post_id not in feed_r, "feed 共 %d 条" % len(feed_r))
    check("8.8c 旁人打开被拒帖被拒（code=404）",
          isinstance(det_r_other, dict) and det_r_other.get("code") == "404",
          "code=%s" % det_r_other.get("code"))
    check("8.8d 作者可见被拒帖且带出拒绝理由",
          isinstance(det_r_author, dict) and det_r_author.get("data", {}).get("rejectReason") == EXPECT_REJECT_REASON,
          det_r_author.get("data", {}).get("rejectReason") if isinstance(det_r_author, dict) else det_r_author)

    re_row = rows_json(h, "better_school",
                       "SELECT action, source, operator_id, reason, reason_code FROM content_review "
                       "WHERE target_type='POST' AND target_id=%s AND action='REJECT'" % reject_post_id)
    check("8.9a content_review 落 REJECT 裁决行（恰 1 行）", len(re_row) == 1, "%d 行" % len(re_row))
    check("8.9b 裁决行 reason=枚举文案：补充说明，reason_code 落库，operator_id=ADMIN",
          len(re_row) == 1 and re_row[0]["reason"] == EXPECT_REJECT_REASON
          and re_row[0]["reason_code"] == REJECT_REASON_CODE and str(re_row[0]["operator_id"]) == str(admin_id)
          and re_row[0]["source"] == "MANUAL",
          re_row[0] if re_row else None)

    re_notice = h.sql("better_school",
                      "SELECT type, content FROM notification WHERE user_id=%s AND type='AUDIT_RESULT' "
                      "AND ref_id=%s" % (author_id, reject_post_id))
    check("8.10a 作者收到 AUDIT_RESULT 通知行", len(re_notice) >= 1, "%d 行" % len(re_notice))
    check("8.10b 驳回通知内容含拒绝理由", any(REJECT_SUPPLEMENT in r[1] for r in re_notice), re_notice)

    items_final, _ = queue_items(h, admin_tok, "POST")
    final_ids = [int(i["targetId"]) for i in items_final]
    check("8.11 已驳回目标移出审核队列",
          reject_post_id not in final_ids and approved_post_id not in final_ids, "队列 %s" % final_ids)

    # --- 4) 拒绝理由必填（负例，证明"带理由"这条口径不是摆设） ---
    resp_fixture = h.ok("POST", "/api/community/posts",
                        {"title": "冒烟缺理由帖", "content": "缺理由负例 " + CLEAN_MARK + " " + RUN_ID,
                         "category": "DAILY"}, token=author_tok)
    fixture_id = int(resp_fixture)
    st_nr, resp_nr = h.call("POST", "/api/admin/reviews/decide",
                            {"targetType": "POST", "targetId": fixture_id, "approve": False}, token=admin_tok)
    state_nr = h.one("better_school", "SELECT state FROM post WHERE post_id=%s" % fixture_id)
    h.evidence("08-reject-missing-reason", {"targetId": fixture_id, "http_status": st_nr, "response": resp_nr,
                                            "state_after": state_nr})
    check("8.12a 缺 reasonCode 的驳回被 400 拦下", isinstance(resp_nr, dict) and resp_nr.get("code") == "400",
          "code=%s" % (resp_nr.get("code") if isinstance(resp_nr, dict) else resp_nr))
    check("8.12b 缺理由裁决未生效（仍 PENDING_REVIEW）", state_nr == "PENDING_REVIEW", "state=%s" % state_nr)
    return {"approve_post_id": approved_post_id, "reject_post_id": reject_post_id, "missing_reason_post_id": fixture_id}


# ---------------- 项 8（续）：评论目标的队列 / 通过 / 驳回 ----------------
def item8_comments(h, admin_tok, admin_id, commenter_tok, commenter_id, post_id, ids):
    """COMMENT 目标的审核：队列可拉 + 驳回带理由改状态 + 通过后对外可见 + 通知落库。

    与 POST 同一套统一裁决用例（ReviewCase.decide），只是 targetType=COMMENT；
    评论通过后还要看"帖被评"互动通知只发给帖主。
    """
    print("\n=== 项 8（续）：后台审核（COMMENT 目标） ===")
    approve_cid = ids.get("approve_comment_id")
    reject_cid = ids.get("reject_comment_id")
    if not approve_cid or not reject_cid:
        check("8.C0 两条待审评论就绪", False, "approve=%s reject=%s" % (approve_cid, reject_cid))
        return

    items, total = queue_items(h, admin_tok, "COMMENT")
    queue_ids = [int(i["targetId"]) for i in items]
    h.evidence("08-comment-queue-before", {"targetType": "COMMENT", "total": total, "items": items})
    check("8.C1a ADMIN 可拉评论审核队列", total >= 2, "total=%d" % total)
    check("8.C1b 队列含待通过与待驳回两条评论",
          approve_cid in queue_ids and reject_cid in queue_ids, "队列 %s" % queue_ids)

    # 驳回：理由必填负例 + 正例
    st_nr, resp_nr = h.call("POST", "/api/admin/reviews/decide",
                            {"targetType": "COMMENT", "targetId": reject_cid, "approve": False}, token=admin_tok)
    state_nr = h.one("better_school", "SELECT state FROM post_comment WHERE comment_id=%s" % reject_cid)
    check("8.C2a 评论缺 reasonCode 的驳回被 400 拦下", isinstance(resp_nr, dict) and resp_nr.get("code") == "400",
          "code=%s" % (resp_nr.get("code") if isinstance(resp_nr, dict) else resp_nr))
    check("8.C2b 缺理由裁决未生效（仍 PENDING_REVIEW）", state_nr == "PENDING_REVIEW", "state=%s" % state_nr)

    comment_reason = "含不友善内容"
    st_r, resp_r = h.call("POST", "/api/admin/reviews/decide",
                          {"targetType": "COMMENT", "targetId": reject_cid, "approve": False,
                           "reasonCode": "OTHER", "supplement": comment_reason}, token=admin_tok)
    rstate = h.one("better_school", "SELECT state FROM post_comment WHERE comment_id=%s" % reject_cid)
    h.evidence("08-comment-reject", {"targetType": "COMMENT", "targetId": reject_cid,
        "missing_reason": {"http_status": st_nr, "response": resp_nr, "state_after": state_nr},
        "reject": {"http_status": st_r, "response": resp_r, "state_after": rstate},
        "content_review": rows_json(h, "better_school",
            "SELECT target_type, target_id, action, source, operator_id, reason, reason_code FROM content_review "
            "WHERE target_type='COMMENT' AND target_id=%s" % reject_cid),
        "notification": rows_json(h, "better_school",
            "SELECT user_id, type, content, ref_type, ref_id FROM notification "
            "WHERE user_id=%s AND type='AUDIT_RESULT' AND ref_id=%s" % (commenter_id, reject_cid))})
    check("8.C3 API 驳回评论成功", st_r == 200 and isinstance(resp_r, dict) and resp_r.get("code") in ("200", "0000"),
          "HTTP %s code=%s" % (st_r, resp_r.get("code") if isinstance(resp_r, dict) else resp_r))
    check("8.C4a 驳回后评论状态转 REJECTED", rstate == "REJECTED", "state=%s" % rstate)
    c_re_row = rows_json(h, "better_school",
                         "SELECT action, source, operator_id, reason, reason_code FROM content_review "
                         "WHERE target_type='COMMENT' AND target_id=%s AND action='REJECT'" % reject_cid)
    check("8.C4b content_review 落 REJECT 裁决行（MANUAL / ADMIN / 带理由）",
          len(c_re_row) == 1 and c_re_row[0]["source"] == "MANUAL"
          and str(c_re_row[0]["operator_id"]) == str(admin_id)
          and c_re_row[0]["reason"] == "其他：" + comment_reason,
          c_re_row[0] if c_re_row else None)
    c_re_notice = h.sql("better_school", "SELECT content FROM notification WHERE user_id=%s "
                                         "AND type='AUDIT_RESULT' AND ref_id=%s" % (commenter_id, reject_cid))
    check("8.C4c 评论作者收到含理由的通知行",
          len(c_re_notice) >= 1 and any(comment_reason in r[0] for r in c_re_notice), c_re_notice)

    # 通过：评论区对外可见 + 帖主收互动通知
    st_a, resp_a = h.call("POST", "/api/admin/reviews/decide",
                          {"targetType": "COMMENT", "targetId": approve_cid, "approve": True}, token=admin_tok)
    astate = h.one("better_school", "SELECT state FROM post_comment WHERE comment_id=%s" % approve_cid)
    _, total_guest = comment_ids(h, post_id)
    h.evidence("08-comment-approve", {"targetType": "COMMENT", "targetId": approve_cid, "post_id": post_id,
        "http_status": st_a, "response": resp_a, "state_after": astate, "guest_visible_total": total_guest,
        "content_review": rows_json(h, "better_school",
            "SELECT target_type, target_id, action, source, operator_id, reason FROM content_review "
            "WHERE target_type='COMMENT' AND target_id=%s" % approve_cid),
        "post_owner_notification": rows_json(h, "better_school",
            "SELECT user_id, type, content, ref_type, ref_id FROM notification "
            "WHERE user_id=%s AND type='POST_COMMENTED' AND ref_id=%s" % (ids.get("author_id"), post_id))})
    check("8.C5 API 通过评论成功", st_a == 200 and isinstance(resp_a, dict) and resp_a.get("code") in ("200", "0000"),
          "HTTP %s code=%s" % (st_a, resp_a.get("code") if isinstance(resp_a, dict) else resp_a))
    check("8.C6a 通过后评论状态转 NORMAL", astate == "NORMAL", "state=%s" % astate)
    check("8.C6b 通过后评论对外可见（访客 total=1）", total_guest == 1, "访客 total=%d" % total_guest)
    c_ap_row = rows_json(h, "better_school",
                         "SELECT action, source, operator_id FROM content_review "
                         "WHERE target_type='COMMENT' AND target_id=%s AND action='APPROVE'" % approve_cid)
    check("8.C6c content_review 落 APPROVE 裁决行（MANUAL / ADMIN）",
          len(c_ap_row) == 1 and c_ap_row[0]["source"] == "MANUAL"
          and str(c_ap_row[0]["operator_id"]) == str(admin_id), c_ap_row[0] if c_ap_row else None)

    items_after, _ = queue_items(h, admin_tok, "COMMENT")
    after_ids = [int(i["targetId"]) for i in items_after]
    check("8.C7 两条评论都已移出审核队列",
          approve_cid not in after_ids and reject_cid not in after_ids, "队列 %s" % after_ids)


# ---------------- main ----------------
def main():
    h = Harness("01/08")
    print("== 票 17 冒烟：项 1 + 项 8（号段 %s）" % "137000171xx")

    if not wait_for_stack():
        h.evidence_text("01-08-stack-not-ready", "%s 25 分钟内未返回 HTTP 200，冒烟未运行。\n" % CHECK_PUBLIC)
        print("\n结论：栈未就绪，未执行任何断言。")
        return 2

    author_tok, author_id = login_with_code(h, AUTHOR_PHONE, nickname="冒烟作者")
    commenter_tok, commenter_id = login_with_code(h, COMMENTER_PHONE, nickname="冒烟路人")
    admin_tok, admin_id = promote_admin(h, ADMIN_PHONE)
    h.evidence("01-08-sessions", {"author": {"phone": AUTHOR_PHONE, "user_id": author_id},
                                  "commenter": {"phone": COMMENTER_PHONE, "user_id": commenter_id},
                                  "admin": {"phone": ADMIN_PHONE, "user_id": admin_id}})
    check("0.1 三个账号就绪（author/commenter/admin，admin 角色已提权）",
          all([author_id, commenter_id, admin_id]) and admin_id != author_id,
          "author=%s commenter=%s admin=%s" % (author_id, commenter_id, admin_id))
    role = h.one("better_school", "SELECT role FROM bs_user WHERE user_id = %s" % admin_id)
    check("0.2 admin 账号库中 role=ADMIN", role == "ADMIN", "role=%s" % role)

    # 编排顺序（被业务规则逼出来的）：
    #   项 1 帖子部分 → 项 8（把该帖人工通过）→ 项 1 评论部分（评论只能发在 NORMAL 帖上）→ 项 8 评论部分
    approve_post_id = item1(h, author_tok, author_id, commenter_tok)
    if approve_post_id is None:
        check("1.x 项 1 未产出可用 postId，项 8 / 评论用例无法继续", False, "干净帖发布失败")
    else:
        item8(h, admin_tok, admin_id, author_tok, author_id, approve_post_id)
        comment_ids_map = item1_comments(h, author_tok, author_id, commenter_tok, commenter_id, approve_post_id)
        if comment_ids_map:
            comment_ids_map["author_id"] = author_id
            item8_comments(h, admin_tok, admin_id, commenter_tok, commenter_id, approve_post_id, comment_ids_map)

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = [(n, d) for n, ok, d in CHECKS if not ok]
    summary = {"total": len(CHECKS), "passed": passed, "failed": len(failed),
               "failures": [{"assertion": n, "detail": str(d)} for n, d in failed],
               "results": [{"assertion": n, "ok": ok, "detail": str(d)} for n, ok, d in CHECKS]}
    h.evidence("01-08-summary", summary)
    print("\n===== 汇总：%d/%d PASS，%d FAIL =====" % (passed, len(CHECKS), len(failed)))
    for n, d in failed:
        print("  FAIL | %s — %s" % (n, d))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
