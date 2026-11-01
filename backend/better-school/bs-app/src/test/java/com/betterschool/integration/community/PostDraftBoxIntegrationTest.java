package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子草稿箱（票 02）集成测试：保存（可留空、不做初筛、仅本人可见）、编辑、
 * 提交（缺必填拦截、初筛命中停留草稿，补全后进待审）、待审撤回回草稿箱、
 * 被拒转草稿可见拒绝理由（重提轮次 +1，两轮审核记录并存）、作者软删。
 * 轮次口径：post.review_round = 累计进入待审次数，重提时由提交语句 +1（content_review.review_round 取此列）。
 */
class PostDraftBoxIntegrationTest extends CommunityApiTestBase {

    // ---------- 保存草稿：部分字段可存、不做初筛、仅本人可见 ----------

    @Test
    void 帖子草稿部分字段保存成功_不做初筛_仅本人可见() {
        Session author = registerAndLogin("13800001001", "作者1");
        Session other = registerAndLogin("13800001002", "路人1");

        // 未填完：只有标题；且正文含敏感词——草稿保存不做初筛，直接发布则会被拦
        JsonNode save = postJson("/api/community/posts/draft",
                Map.of("title", "备忘：作弊器调研"), author);
        assertSuccess(save, "保存帖子草稿");
        String postId = save.path("data").asText();

        assertThat(postStatus(postId)).as("草稿状态应为 DRAFT").isEqualTo("DRAFT");

        JsonNode publicList = getJson("/api/community/posts", other);
        assertThat(postIdsOf(publicList)).as("草稿不应对外可见").doesNotContain(postId);

        JsonNode otherMine = getJson("/api/community/posts/mine", other);
        assertThat(postIdsOf(otherMine)).as("他人我的发帖不含该草稿").doesNotContain(postId);

        JsonNode otherDetail = getJson("/api/community/posts/" + postId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看草稿详情应 404：%s", otherDetail).isEqualTo("404");

        JsonNode mine = getJson("/api/community/posts/mine", author);
        List<JsonNode> mineItems = StreamSupport.stream(mine.path("data").path("list").spliterator(), false)
                .filter(node -> postId.equals(node.path("postId").asText())).toList();
        assertThat(mineItems).as("我的发帖应含自己的草稿").hasSize(1);
        assertThat(mineItems.get(0).path("status").asText()).isEqualTo("DRAFT");

        JsonNode ownerDetail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(ownerDetail, "本人看自己的草稿");
        assertThat(ownerDetail.path("data").path("title").asText()).isEqualTo("备忘：作弊器调研");
    }

    // ---------- 编辑草稿 ----------

    @Test
    void 帖子草稿可编辑全部内容并回显() {
        Session author = registerAndLogin("13800001003", "作者2");
        String postId = savePostDraft(author, "旧草稿");

        JsonNode update = putJson("/api/community/posts/" + postId + "/draft", Map.ofEntries(
                Map.entry("title", "失物招领：蓝色水杯"),
                Map.entry("content", "在三食堂捡到蓝色保温杯，联系我认领"),
                Map.entry("images", List.of("http://minio/bs-files/it/post-draft-1.jpg"))), author);
        assertSuccess(update, "编辑帖子草稿");

        JsonNode detail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(detail, "回看编辑后的草稿");
        JsonNode data = detail.path("data");
        assertThat(data.path("title").asText()).isEqualTo("失物招领：蓝色水杯");
        assertThat(data.path("content").asText()).isEqualTo("在三食堂捡到蓝色保温杯，联系我认领");
        assertThat(data.path("images")).hasSize(1);
        assertThat(data.path("status").asText()).as("编辑不改状态").isEqualTo("DRAFT");
    }

    // ---------- 提交：缺必填拦截、初筛命中停留草稿、补全后进待审 ----------

    @Test
    void 不完整草稿提交失败提示补全_补全后进待审() {
        Session author = registerAndLogin("13800001004", "作者3");
        String postId = savePostDraft(author, "只有标题的草稿");

        // 缺正文（唯一必填项，feed-index-uv 票 06 分类退役）：提交被完整性校验拦截
        JsonNode response = postJson("/api/community/posts/" + postId + "/submit", null, author);
        assertThat(response.path("code").asText()).as("缺必填项应拒绝提交：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).contains("不完整");
        assertThat(postStatus(postId)).as("校验失败停留草稿").isEqualTo("DRAFT");

        // 补正文后提交成功进待审
        assertSuccess(putJson("/api/community/posts/" + postId + "/draft",
                Map.of("content", "正文补上了"), author), "补正文");
        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "补全后提交");
        assertThat(postStatus(postId)).as("先审后发：提交即待审").isEqualTo("PENDING_REVIEW");
    }

    @Test
    void 草稿提交初筛命中停留草稿_改后提交进入待审() {
        Session author = registerAndLogin("13800001005", "作者4");
        String postId = savePostDraft(author, "九成新吉他转让");

        // 编辑带入违禁词后提交：初筛命中应失败，且草稿原样保留可继续改
        updatePostDraft(author, postId, "九成新吉他转让", "可代考复习资料");
        JsonNode hit = postJson("/api/community/posts/" + postId + "/submit", null, author);
        assertThat(hit.path("code").asText()).as("初筛命中应业务失败：%s", hit).isEqualTo("500");
        assertThat(hit.path("info").asText()).contains("违规词汇");
        assertThat(postStatus(postId)).as("命中后停留草稿").isEqualTo("DRAFT");
        assertThat(countRows("post")).as("命中不新增行").isEqualTo(1);

        // 改掉违禁词再提交：进待审（先审后发）
        updatePostDraft(author, postId, "九成新吉他转让", "去年考证用过的资料");
        JsonNode retry = postJson("/api/community/posts/" + postId + "/submit", null, author);
        assertSuccess(retry, "改后重新提交");
        assertThat(postStatus(postId)).as("先审后发：提交即待审").isEqualTo("PENDING_REVIEW");
        Integer reviewRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM post WHERE post_id = ?", Integer.class, postId);
        assertThat(reviewRound).as("首次提交进入待审，轮次 0 → 1").isEqualTo(1);
        java.time.LocalDateTime enteredAt = jdbcTemplate.queryForObject(
                "SELECT review_entered_at FROM post WHERE post_id = ?", java.time.LocalDateTime.class, postId);
        assertThat(enteredAt).as("进入待审时间应被锚定").isNotNull();

        JsonNode publicList = getJson("/api/community/posts", author);
        assertThat(postIdsOf(publicList)).as("待审对外仍不可见").doesNotContain(postId);
    }

    // ---------- 待审撤回 → 草稿箱 → 编辑重提（轮次 +1） ----------

    @Test
    void 待审帖不可编辑_可撤回回草稿箱_编辑重提轮次加一() {
        Session author = registerAndLogin("13800001006", "作者5");
        Session other = registerAndLogin("13800001007", "路人2");
        String postId = createPendingPost(author, "待审中的帖子");

        JsonNode editPending = putJson("/api/community/posts/" + postId + "/draft",
                Map.of("title", "改标题"), author);
        assertThat(editPending.path("code").asText())
                .as("待审中不可编辑：%s", editPending).isEqualTo("500");
        assertThat(editPending.path("info").asText()).contains("撤回");

        // 非作者撤回应 403
        JsonNode forbidden = postJson("/api/community/posts/" + postId + "/withdraw", null, other);
        assertThat(forbidden.path("code").asText()).as("非作者撤回应 403：%s", forbidden).isEqualTo("403");

        JsonNode withdraw = postJson("/api/community/posts/" + postId + "/withdraw", null, author);
        assertSuccess(withdraw, "待审撤回");
        assertThat(postStatus(postId)).as("撤回回草稿箱").isEqualTo("DRAFT");

        // 撤回后可编辑，重新提交回到待审（轮次 +1）
        assertSuccess(putJson("/api/community/posts/" + postId + "/draft",
                Map.of("title", "改好标题的帖子", "content", "撤回后修改过的正文",
                        "images", List.of()), author), "撤回后编辑");
        JsonNode resubmit = postJson("/api/community/posts/" + postId + "/submit", null, author);
        assertSuccess(resubmit, "草稿重新提交");
        assertThat(postStatus(postId)).as("重新提交回到待审").isEqualTo("PENDING_REVIEW");
        Integer reviewRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM post WHERE post_id = ?", Integer.class, postId);
        assertThat(reviewRound).as("重提应轮次 +1 进第二轮").isEqualTo(2);
    }

    // ---------- 被拒转草稿：可见拒绝理由、重提轮次 +1、两轮审核记录并存 ----------

    @Test
    void 被拒帖转草稿可见拒绝理由_重提轮次加一且两轮审核记录并存() {
        Session author = registerAndLogin("13800001008", "作者6");
        Session other = registerAndLogin("13800001009", "路人3");
        String postId = createPendingPost(author, "会被拒绝的帖子");
        Session admin = adminSession();
        String firstReason = "内容与板块不符";
        rejectPost(admin, postId, firstReason);
        assertThat(postStatus(postId)).isEqualTo("REJECTED");

        // 被拒详情仅作者可见，且能看到拒绝理由
        JsonNode otherDetail = getJson("/api/community/posts/" + postId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看被拒帖应 404：%s", otherDetail).isEqualTo("404");
        JsonNode ownerDetail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(ownerDetail, "作者看被拒帖");
        assertThat(ownerDetail.path("data").path("rejectReason").asText()).isEqualTo("其他：" + firstReason);

        // 我的列表（草稿箱数据源）：REJECTED 行直接携带拒绝理由，前端列表免二次取详情
        JsonNode rejectedRow = mineRow(getJson("/api/community/posts/mine", author), postId);
        assertThat(rejectedRow.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectedRow.path("rejectReason").asText()).isEqualTo("其他：" + firstReason);

        // 仅被拒帖可转草稿箱（在售/正常帖先验证闸门）
        String normalId = createPendingPost(author, "正常待审帖");
        JsonNode guard = postJson("/api/community/posts/" + normalId + "/to-draft", null, author);
        assertThat(guard.path("code").asText()).as("仅被拒帖可转草稿箱：%s", guard).isEqualTo("500");

        // 被拒 → 转草稿箱 → 编辑 → 重提回待审（轮次 +1）
        assertSuccess(postJson("/api/community/posts/" + postId + "/to-draft", null, author), "被拒转草稿箱");
        assertThat(postStatus(postId)).as("被拒帖应转入草稿箱").isEqualTo("DRAFT");
        JsonNode draftDetail = getJson("/api/community/posts/" + postId, author);
        assertThat(draftDetail.path("data").path("rejectReason").asText())
                .as("转草稿箱后拒绝理由仍可见").isEqualTo("其他：" + firstReason);
        assertSuccess(putJson("/api/community/posts/" + postId + "/draft",
                Map.of("title", "改后重新提的帖子", "content", "按拒绝理由修改后的正文",
                        "images", List.of()), author), "草稿编辑");
        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "草稿重新提交");
        assertThat(postStatus(postId)).isEqualTo("PENDING_REVIEW");
        Integer reviewRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM post WHERE post_id = ?", Integer.class, postId);
        assertThat(reviewRound).as("重提应轮次 +1 进第二轮").isEqualTo(2);

        // 第二轮再次拒绝：两次审核记录并存，拒绝理由取最新
        String secondReason = "重提后内容仍不合规";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false, "reasonCode", "OTHER", "supplement", secondReason), admin),
                "第二轮拒绝");
        assertThat(postStatus(postId)).isEqualTo("REJECTED");
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'POST' AND target_id = ?",
                Integer.class, postId);
        assertThat(reviewRows).as("两轮审核记录并存").isEqualTo(2);
        List<Integer> rounds = jdbcTemplate.queryForList(
                "SELECT review_round FROM content_review WHERE target_type = 'POST' AND target_id = ? " +
                        "AND action = 'REJECT' ORDER BY review_round", Integer.class, postId);
        assertThat(rounds).as("两轮拒绝分别落轮次 1、2").containsExactly(1, 2);
        JsonNode latestDetail = getJson("/api/community/posts/" + postId, author);
        assertThat(latestDetail.path("data").path("rejectReason").asText())
                .as("拒绝理由按最新一次展示").isEqualTo("其他：" + secondReason);
        assertThat(mineRow(getJson("/api/community/posts/mine", author), postId).path("rejectReason").asText())
                .as("我的列表拒绝理由同样取最新一轮").isEqualTo("其他：" + secondReason);

        // 再次被拒仍可转草稿箱（自救路径可重复走）
        assertSuccess(postJson("/api/community/posts/" + postId + "/to-draft", null, author), "再次被拒转草稿箱");
        assertThat(postStatus(postId)).isEqualTo("DRAFT");
    }

    // ---------- 作者软删任意状态帖子 ----------

    @Test
    void 作者可软删草稿与被拒帖() {
        Session author = registerAndLogin("13800001010", "作者7");
        String draftId = savePostDraft(author, "不要了的草稿");

        assertSuccess(deleteJson("/api/community/posts/" + draftId, author), "删除草稿");
        assertThat(postStatus(draftId)).as("草稿软删").isEqualTo("DELETED");
        JsonNode detail = getJson("/api/community/posts/" + draftId, author);
        assertThat(detail.path("code").asText()).as("已删草稿详情不可见").isEqualTo("404");

        String rejectedId = createPendingPost(author, "被拒后删除的帖子");
        Session admin = adminSession();
        rejectPost(admin, rejectedId, "不合规");
        assertSuccess(deleteJson("/api/community/posts/" + rejectedId, author), "删除被拒帖");
        assertThat(postStatus(rejectedId)).isEqualTo("DELETED");
    }

    // ---------- 工具 ----------

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900001001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900001001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900001001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900001001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    private String savePostDraft(Session author, String title) {
        JsonNode save = postJson("/api/community/posts/draft", Map.of("title", title), author);
        assertSuccess(save, "保存帖子草稿");
        return save.path("data").asText();
    }

    /** 我的列表中指定帖子的行（草稿箱/我的-发帖列表断言用） */
    private JsonNode mineRow(JsonNode mine, String postId) {
        return StreamSupport.stream(mine.path("data").path("list").spliterator(), false)
                .filter(node -> postId.equals(node.path("postId").asText()))
                .findFirst()
                .orElseThrow();
    }

    private void updatePostDraft(Session author, String postId, String title, String content) {
        JsonNode update = putJson("/api/community/posts/" + postId + "/draft", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("content", content),
                Map.entry("images", List.of())), author);
        assertSuccess(update, "编辑帖子草稿");
    }

    /** 直接发帖（先审后发 → PENDING_REVIEW），返回 postId */
    private String createPendingPost(Session author, String title) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", title, "content", "测试正文内容"), author);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        assertThat(postStatus(postId)).as("先审后发：提交即待审").isEqualTo("PENDING_REVIEW");
        return postId;
    }

    private void rejectPost(Session admin, String postId, String reason) {
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "帖子裁决拒绝");
        assertThat(postStatus(postId)).isEqualTo("REJECTED");
    }

    private String postStatus(String postId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> postIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("postId").asText()).toList();
    }
}
