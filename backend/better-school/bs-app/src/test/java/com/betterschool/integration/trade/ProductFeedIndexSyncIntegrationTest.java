package com.betterschool.integration.trade;

import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import com.betterschool.infrastructure.adapter.repository.CountAction;
import com.betterschool.infrastructure.adapter.repository.CountEvent;
import com.betterschool.infrastructure.adapter.repository.trade.TradeRepository;
import com.betterschool.infrastructure.adapter.repository.LikeState;
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
 * 草稿提交行诞生/翻回（下架→重提刷新 feed_time 与 price 快照）、五态流转的状态快照逐点镜像、
 * 在售改价的 price 同步、浏览合并事务的 view_count ±N 旁路。
 *
 * <p>断言只看外部可观察状态：product_feed_index 行的 state / feed_time / price / view_count。
 * 流转走真实仓储链路（真实事务、真实 SQL），从未上架过的「0 行静默」与条件 UPDATE 0 行的
 * 「索引不复活」闸门一并钉死。
 */
@DisplayName("商品 feed 索引同步：提交诞生/翻回 / 状态快照逐点 / 改价同步 / 浏览旁路 ±N")
class ProductFeedIndexSyncIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ITradeRepository tradeRepository;

    @Autowired
    private TradeRepository flushHandler;

    @Test
    @DisplayName("草稿提交诞生索引行（price 快照随行）；下架→重提 ODKU 刷新 feed_time 与 price")
    void 草稿提交诞生索引行_下架重提刷新feed_time与价格() {
        String productId = insertProduct("DRAFT", "10.00");

        LocalDateTime firstSubmitAt = LocalDateTime.now();
        assertThat(tradeRepository.submitDraft(productId)).as("草稿提交即上架").isTrue();

        Map<String, Object> row = feedRow(productId);
        assertThat(row).as("提交后索引行诞生").isNotNull();
        assertThat(row.get("state")).isEqualTo("ON_SALE");
        assertThat(((BigDecimal) row.get("price"))).as("price 快照 = submit 彼时主表价格").isEqualByComparingTo("10.00");
        assertThat(((Number) row.get("view_count")).intValue()).as("诞生时热度键为 0").isZero();
        assertThat(((LocalDateTime) row.get("feed_time")))
                .as("feed_time = 第一次提交上架时刻")
                .isBetween(firstSubmitAt.minusSeconds(5), LocalDateTime.now().plusSeconds(5));

        // 下架回草稿（混提新价 20）→ 重提：ODKU 刷新 feed_time 与 price 快照。
        // DATETIME 秒级精度下「同秒提交」区分不出刷新与否：先把首轮 feed_time 拨回一小时前，
        // 再断言重提后 feed_time 追上当下——不刷新的话拨回的旧值会原样留存
        LocalDateTime resubmitAt = LocalDateTime.now();
        jdbcTemplate.update("UPDATE product_feed_index SET feed_time = DATE_SUB(NOW(), INTERVAL 1 HOUR) "
                + "WHERE product_id = ?", productId);
        assertThat(tradeRepository.takeDown(productId)).as("在售下架汇入草稿箱").isTrue();
        assertThat(feedState(productId)).as("下架期间索引行不可见（state 快照 DRAFT）")
                .isEqualTo("DRAFT");

        ProductEntity editEntity = new ProductEntity();
        editEntity.setProductId(productId);
        editEntity.setTitle("索引同步夹具");
        editEntity.setDescription("夹具描述");
        editEntity.setPrice(new BigDecimal("20.00"));
        editEntity.setTradeLocation("驿站");
        editEntity.setCategory(ProductCategoryVO.BOOKS);
        editEntity.setStatus(ProductStatusVO.DRAFT);
        tradeRepository.updateDraft(editEntity);

        assertThat(tradeRepository.submitDraft(productId)).as("重提再上架").isTrue();
        Map<String, Object> resubmitted = feedRow(productId);
        assertThat(resubmitted.get("state")).isEqualTo("ON_SALE");
        assertThat(((LocalDateTime) resubmitted.get("feed_time")))
                .as("重提刷新 feed_time（进入当前对外可见轮；重灌时统一归位 create_time 口径）")
                .isAfterOrEqualTo(resubmitAt.minusSeconds(5));
        assertThat(((BigDecimal) resubmitted.get("price")))
                .as("重提刷新 price 快照（草稿期混提的新价恢复可见即生效）")
                .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("状态快照逐点镜像状态流转（台账口径）；从未上架的流转点 0 行静默不产行；删除后条件 UPDATE 不复活索引")
    void 状态快照逐点镜像_未上架过不产行_删除后不复活() {
        // 主链：下架回草稿 → 重提 → 下单扣减售罄 → 取消回补翻回在售 → 再售罄（multi-quantity-listing）
        String chainId = insertProduct("ON_SALE", "10.00");
        insertInventory(chainId, 1);
        assertThat(tradeRepository.takeDown(chainId)).isTrue();
        assertThat(feedState(chainId)).as("下架汇入草稿箱 → DRAFT").isEqualTo("DRAFT");
        tradeRepository.submitDraft(chainId);
        assertThat(feedState(chainId)).as("重提 → ON_SALE（翻回可见）").isEqualTo("ON_SALE");
        String orderId = placeOrder(chainId, "buyer-1");
        assertThat(feedState(chainId)).as("下单扣到 0 件 → SOLD（售罄随行翻态）").isEqualTo("SOLD");
        tradeRepository.cancelOrderWithSearchSync(orderId, "buyer-1");
        assertThat(feedState(chainId)).as("取消回补 remaining > 0 → ON_SALE（翻回可见）").isEqualTo("ON_SALE");
        String orderId2 = placeOrder(chainId, "buyer-2");
        assertThat(feedState(chainId)).as("再次售罄 → SOLD").isEqualTo("SOLD");

        // 卖家标记售出 + 库存闸门下的重新上架（SOLD → ON_SALE 直接回大厅）
        tradeRepository.cancelOrderWithSearchSync(orderId2, "buyer-2");
        assertThat(feedState(chainId)).as("回补翻回 ON_SALE").isEqualTo("ON_SALE");
        tradeRepository.markSold(chainId);
        assertThat(feedState(chainId)).as("卖家标记售出 → SOLD（台账剩余同步清零）").isEqualTo("SOLD");
        assertThat(tradeRepository.relist(chainId))
                .as("真卖完（remaining = 0）重上架被库存闸门拦下").isFalse();
        assertThat(feedState(chainId)).as("闸门拦下索引仍 SOLD").isEqualTo("SOLD");
        tradeRepository.restock(chainId, 1, "999");
        assertThat(tradeRepository.relist(chainId)).as("加货后重上架 → ON_SALE").isTrue();
        assertThat(feedState(chainId)).as("重新上架直接回大厅 → ON_SALE").isEqualTo("ON_SALE");

        // 软删：行翻 DELETED 不消失；此态下条件 UPDATE 未命中，索引必须不被复活
        String deletedId = insertProduct("ON_SALE", "40.00");
        insertInventory(deletedId, 1);
        tradeRepository.deleteById(deletedId);
        assertThat(feedState(deletedId)).as("软删 → DELETED").isEqualTo("DELETED");
        assertThat(tradeRepository.relist(deletedId))
                .as("对已删商品 relist 必然 0 行").isFalse();
        assertThat(feedState(deletedId))
                .as("条件 UPDATE 未命中不得写索引（否则已删商品在 feed 复活）").isEqualTo("DELETED");

        // 从未上架过（草稿常态，行不存在）：状态流转点 0 行静默，不凭空产行
        String draftId = insertProduct("DRAFT", "50.00");
        assertThat(tradeRepository.takeDown(draftId))
                .as("草稿商品下架必然 0 行").isFalse();
        assertThat(feedRow(draftId)).as("草稿下架（行不存在）0 行静默").isNull();
        assertThat(tradeRepository.relist(draftId))
                .as("草稿商品 relist（无台账行）必然拒绝").isFalse();
        assertThat(feedRow(draftId)).as("草稿 relist（行不存在）不产索引行").isNull();
    }

    @Test
    @DisplayName("在售改价立即生效：索引 price 列同事务跟上（价格排序键不漂移）")
    void 在售改价索引同事务跟上() {
        String productId = insertProduct("ON_SALE", "10.00");

        assertThat(tradeRepository.updateOnSalePrice(productId, new BigDecimal("66.60"))).isTrue();

        assertThat(feedPrice(productId)).as("索引 price 同事务同步新价").isEqualByComparingTo("66.60");
        BigDecimal mainPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM product WHERE product_id = ?", BigDecimal.class, productId);
        assertThat(mainPrice).as("主表价格同步（现状语义不变）").isEqualByComparingTo("66.60");
    }

    @Test
    @DisplayName("浏览合并事务旁路 ±N 推进热度键；0 行不补行（未上架商品的浏览只落明细）")
    void 浏览旁路推进热度键_未上架不补行() {
        String onSaleId = insertProduct("ON_SALE", "10.00");

        List<LikeState> results = flushHandler.flushViewGroup(onSaleId,
                List.of(viewEvent(onSaleId, "u101"), viewEvent(onSaleId, "u101"), viewEvent(onSaleId, "u102")));
        assertThat(results).hasSize(3).containsOnly(LikeState.APPLIED);
        assertThat(indexViewCount(onSaleId)).as("索引热度键按新增 UV 数 +N").isEqualTo(2);
        assertThat(acceleratedCountOf(onSaleId)).as("窄表真源同步推进").isEqualTo(2);

        // 全重复组：INSERT IGNORE 全撞键，索引热度键与窄表都不动（幂等不动点）
        flushHandler.flushViewGroup(onSaleId,
                List.of(viewEvent(onSaleId, "u101")));
        assertThat(indexViewCount(onSaleId)).as("重复浏览不计入").isEqualTo(2);

        // 未上架商品（行不存在）：浏览旁路 0 行不补行，窄表照常推进（交重建 job 兜底）
        String draftId = insertProduct("DRAFT", "10.00");
        flushHandler.flushViewGroup(draftId, List.of(viewEvent(draftId, "u103")));
        assertThat(feedRow(draftId)).as("索引行缺失不被凭空补行").isNull();
        assertThat(acceleratedCountOf(draftId)).as("窄表真源不受索引缺失影响").isEqualTo(1);
    }

    // ---------- 夹具与可观察状态 ----------

    private static CountEvent viewEvent(String productId, String userId) {
        return new CountEvent(CountAction.PRODUCT_VIEW, productId, userId);
    }

    /** 直插一行指定态商品（存量形态；uuid-biz-id 票 05 起业务 id 直插 UUID，应用侧发号），返回业务 ID。
     *  ON_SALE 视作「此前已提交上架」的存量形态，同步直插索引行（行集起点） */
    private String insertProduct(String state, String price) {
        String productId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, 999, '索引同步夹具', '夹具描述', ?, '驿站', 'BOOKS', ?, "
                        + "NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, price, state);
        if ("ON_SALE".equals(state)) {
            jdbcTemplate.update("INSERT INTO product_feed_index (product_id, state, feed_time, create_time, "
                    + "price, view_count, update_time) VALUES (?, 'ON_SALE', NOW(), NOW(), ?, 0, NOW())", productId, price);
        }
        return productId;
    }

    /** 直插一行库存台账（存量形态夹具；应用链路 save() 会同事务建账） */
    private void insertInventory(String productId, int quantity) {
        jdbcTemplate.update("INSERT INTO product_inventory (product_id, quantity, remaining, create_time, update_time) "
                + "VALUES (?, ?, ?, NOW(), NOW())", productId, quantity, quantity);
    }

    /** 真实仓储链路下一件单（下单原子：订单 + 台账扣减 + 流水 + 售罄翻态 + 超时注册） */
    private String placeOrder(String productId, String buyerId) {
        ProductEntity product = tradeRepository.findById(productId);
        ProductOrderEntity order = ProductOrderEntity.create(buyerId, product, 1);
        return tradeRepository.createOrderWithInventory(order, productId, 15);
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
