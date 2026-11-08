package com.betterschool.integration.trade;

import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.infrastructure.adapter.merge.CountAction;
import com.betterschool.infrastructure.adapter.merge.CountEvent;
import com.betterschool.infrastructure.adapter.merge.ICountFlushHandler;
import com.betterschool.infrastructure.adapter.merge.LikeState;
import com.betterschool.integration.support.CommunityApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品 feed 索引的同事务直写触发点（market-feed-uv 票 03，照帖子 PostFeedIndexSyncIntegrationTest 先例）：
 * 审核通过行诞生/翻回（重提再通过刷新 feed_time 与 price 快照）、七态流转的状态快照逐点镜像、
 * 在售改价的 price 同步、浏览合并事务的 view_count ±N 旁路。
 *
 * <p>断言只看外部可观察状态：product_feed_index 行的 state / feed_time / price / view_count。
 * 流转走真实仓储链路（真实事务、真实 SQL），从未通过审的「0 行静默」与条件 UPDATE 0 行的
 * 「索引不复活」闸门一并钉死。
 */
@DisplayName("商品 feed 索引同步：通过诞生/翻回 / 状态快照逐点 / 改价同步 / 浏览旁路 ±N")
class ProductFeedIndexSyncIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ITradeRepository tradeRepository;

    @Autowired
    private ICountFlushHandler flushHandler;

    

    @Test
    @DisplayName("审核通过诞生索引行（price 快照随行）；重提再通过 ODKU 刷新 feed_time 与 price")
    void 审核通过诞生索引行_重提再通过刷新feed_time与价格() {
        String productId = insertProduct("PENDING_REVIEW", "10.00");

        LocalDateTime firstApproveAt = LocalDateTime.now();
        assertThat(tradeRepository.reviewApproveById(productId)).as("待审商品通过").isTrue();

        Map<String, Object> row = feedRow(productId);
        assertThat(row).as("通过后索引行诞生").isNotNull();
        assertThat(row.get("state")).isEqualTo("ON_SALE");
        assertThat(((BigDecimal) row.get("price"))).as("price 快照 = approve 彼时主表价格").isEqualByComparingTo("10.00");
        assertThat(((Number) row.get("view_count")).intValue()).as("诞生时热度键为 0").isZero();
        assertThat(((LocalDateTime) row.get("feed_time")))
                .as("feed_time = 第一次通过时刻")
                .isBetween(firstApproveAt.minusSeconds(5), LocalDateTime.now().plusSeconds(5));

        // 在售改内容回审（混提新价 20）→ 不可见化 → 再通过：ODKU 刷新 feed_time 与 price 快照。
        // DATETIME 秒级精度下「同秒通过」区分不出刷新与否：先把首轮 feed_time 拨回一小时前，
        // 再断言重提轮通过后 feed_time 追上当下——不刷新的话拨回的旧值会原样留存
        LocalDateTime reapproveAt = LocalDateTime.now();
        jdbcTemplate.update("UPDATE product_feed_index SET feed_time = DATE_SUB(NOW(), INTERVAL 1 HOUR) "
                + "WHERE product_id = ?", productId);
        ProductEntity editEntity = new ProductEntity();
        editEntity.setProductId(productId);
        editEntity.setPrice(new BigDecimal("20.00"));
        assertThat(tradeRepository.editOnSaleContent(editEntity)).as("在售改内容回待审").isTrue();
        assertThat(feedState(productId)).as("回审期间索引行不可见（state 快照 PENDING_REVIEW）")
                .isEqualTo("PENDING_REVIEW");

        assertThat(tradeRepository.reviewApproveById(productId)).as("重提再通过").isTrue();
        Map<String, Object> reapproved = feedRow(productId);
        assertThat(reapproved.get("state")).isEqualTo("ON_SALE");
        assertThat(((LocalDateTime) reapproved.get("feed_time")))
                .as("重提再通过刷新 feed_time（进入当前对外可见轮，与票 01 回填 / 票 06 重建的 MAX(APPROVE) 同口径）")
                .isAfterOrEqualTo(reapproveAt.minusSeconds(5));
        assertThat(((BigDecimal) reapproved.get("price")))
                .as("重提再通过刷新 price 快照（回审期混提的新价恢复可见即生效）")
                .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("状态快照逐点镜像七态流转；从未通过审的流转点 0 行静默不产行；删除后 release 不复活索引")
    void 状态快照逐点镜像_未通过审不产行_删除后不复活() {
        // 主链：通过 → 下架回草稿 → 重提 → 锁定 → 释放回在售 → 锁定 → 已出
        String chainId = insertProduct("PENDING_REVIEW", "10.00");
        tradeRepository.reviewApproveById(chainId);
        tradeRepository.takeDown(chainId);
        assertThat(feedState(chainId)).as("下架汇入草稿箱 → DRAFT").isEqualTo("DRAFT");
        tradeRepository.submitDraft(chainId);
        assertThat(feedState(chainId)).as("重提 → PENDING_REVIEW").isEqualTo("PENDING_REVIEW");
        tradeRepository.reviewApproveById(chainId);
        tradeRepository.lock(chainId);
        assertThat(feedState(chainId)).as("下单锁定 → LOCKED").isEqualTo("LOCKED");
        tradeRepository.release(chainId);
        assertThat(feedState(chainId)).as("取消订单释放 → ON_SALE（翻回可见）").isEqualTo("ON_SALE");
        tradeRepository.lock(chainId);
        tradeRepository.lockToSold(chainId);
        assertThat(feedState(chainId)).as("确认收货 → SOLD").isEqualTo("SOLD");

        // 卖家标记售出 + 重新上架
        String soldId = insertProduct("PENDING_REVIEW", "30.00");
        tradeRepository.reviewApproveById(soldId);
        tradeRepository.markSold(soldId);
        assertThat(feedState(soldId)).as("卖家标记售出 → SOLD").isEqualTo("SOLD");
        tradeRepository.relist(soldId);
        assertThat(feedState(soldId)).as("重新上架回待审 → PENDING_REVIEW").isEqualTo("PENDING_REVIEW");

        // 软删：行翻 DELETED 不消失；此态下 release 条件 UPDATE 0 行，索引必须不被复活
        String deletedId = insertProduct("PENDING_REVIEW", "40.00");
        tradeRepository.reviewApproveById(deletedId);
        tradeRepository.deleteById(deletedId);
        assertThat(feedState(deletedId)).as("软删 → DELETED").isEqualTo("DELETED");
        assertThat(tradeRepository.release(deletedId))
                .as("对已删商品 release 必然 0 行").isFalse();
        assertThat(feedState(deletedId))
                .as("条件 UPDATE 未命中不得写索引（否则已删商品在 feed 复活）").isEqualTo("DELETED");

        // 从未通过审：驳回 → 转草稿 → 重提 → 撤回，全程不凭空产行
        String neverId = insertProduct("PENDING_REVIEW", "50.00");
        assertThat(tradeRepository.reviewRejectById(neverId)).isTrue();
        assertThat(feedRow(neverId)).as("驳回不产索引行").isNull();
        assertThat(tradeRepository.rejectToDraft(neverId)).isTrue();
        assertThat(feedRow(neverId)).as("被拒转草稿不产索引行").isNull();
        assertThat(tradeRepository.submitDraft(neverId)).isTrue();
        assertThat(feedRow(neverId)).as("重提（行仍不存在）不产索引行").isNull();
        assertThat(tradeRepository.withdraw(neverId)).isTrue();
        assertThat(feedRow(neverId)).as("撤回（行仍不存在）0 行静默").isNull();
    }

    @Test
    @DisplayName("在售改价立即生效：索引 price 列同事务跟上（价格排序键不漂移）")
    void 在售改价索引同事务跟上() {
        String productId = insertProduct("PENDING_REVIEW", "10.00");
        tradeRepository.reviewApproveById(productId);

        assertThat(tradeRepository.updateOnSalePrice(productId, new BigDecimal("66.60"))).isTrue();

        assertThat(feedPrice(productId)).as("索引 price 同事务同步新价").isEqualByComparingTo("66.60");
        BigDecimal mainPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM product WHERE product_id = ?", BigDecimal.class, productId);
        assertThat(mainPrice).as("主表价格同步（现状语义不变）").isEqualByComparingTo("66.60");
    }

    @Test
    @DisplayName("浏览合并事务旁路 ±N 推进热度键；0 行不补行（未过审商品的浏览只落明细）")
    void 浏览旁路推进热度键_未过审不补行() {
        String onSaleId = insertProduct("PENDING_REVIEW", "10.00");
        tradeRepository.reviewApproveById(onSaleId);

        List<LikeState> results = flushHandler.flushGroup(CountAction.PRODUCT_VIEW, onSaleId,
                List.of(viewEvent(onSaleId, "u101"), viewEvent(onSaleId, "u101"), viewEvent(onSaleId, "u102")));
        assertThat(results).hasSize(3).containsOnly(LikeState.APPLIED);
        assertThat(indexViewCount(onSaleId)).as("索引热度键按新增 UV 数 +N").isEqualTo(2);
        assertThat(acceleratedCountOf(onSaleId)).as("窄表真源同步推进").isEqualTo(2);

        // 全重复组：INSERT IGNORE 全撞键，索引热度键与窄表都不动（幂等不动点）
        flushHandler.flushGroup(CountAction.PRODUCT_VIEW, onSaleId,
                List.of(viewEvent(onSaleId, "u101")));
        assertThat(indexViewCount(onSaleId)).as("重复浏览不计入").isEqualTo(2);

        // 未过审商品（行不存在）：浏览旁路 0 行不补行，窄表照常推进（交重建 job 兜底）
        String pendingId = insertProduct("PENDING_REVIEW", "10.00");
        flushHandler.flushGroup(CountAction.PRODUCT_VIEW, pendingId, List.of(viewEvent(pendingId, "u103")));
        assertThat(feedRow(pendingId)).as("索引行缺失不被凭空补行").isNull();
        assertThat(acceleratedCountOf(pendingId)).as("窄表真源不受索引缺失影响").isEqualTo(1);
    }

    // ---------- 夹具与可观察状态 ----------

    private static CountEvent viewEvent(String productId, String userId) {
        return new CountEvent(CountAction.PRODUCT_VIEW, productId, userId);
    }

    /** 直插一行指定态商品（存量形态；uuid-biz-id 票 05 起业务 id 直插 UUID，应用侧发号），返回业务 ID */
    private String insertProduct(String state, String price) {
        String productId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, review_round, create_time, update_time, expire_at) "
                        + "VALUES (?, 999, '索引同步夹具', '夹具描述', ?, '驿站', 'BOOKS', ?, 1, "
                        + "NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, price, state);
        return productId;
    }

    /** 索引行整行（无行 = null） */
    private Map<String, Object> feedRow(String productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state, feed_time, price, view_count FROM product_feed_index WHERE product_id = ?", productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String feedState(String productId) {
        Map<String, Object> row = feedRow(productId);
        return row == null ? null : (String) row.get("state");
    }

    private BigDecimal feedPrice(String productId) {
        return (BigDecimal) feedRow(productId).get("price");
    }

    private int indexViewCount(String productId) {
        return ((Number) feedRow(productId).get("view_count")).intValue();
    }

    private int acceleratedCountOf(String productId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT view_count FROM product_view_count WHERE product_id = ?", Integer.class, productId);
        return count == null ? 0 : count;
    }
}
