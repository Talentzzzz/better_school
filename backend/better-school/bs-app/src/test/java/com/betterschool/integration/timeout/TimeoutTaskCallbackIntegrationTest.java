package com.betterschool.integration.timeout;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.betterschool.integration.support.TimeoutCallbackFixture.assertCallbackSuccess;
import static com.betterschool.integration.support.TimeoutCallbackFixture.callback;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内回调分发（超时中心内嵌化票 05）集成测试：扫描 case 到点后逐项调用的就是
 * {@code TimeoutTaskCallbackDispatcher}（原独立中心时代的 Dubbo 回调 Provider 已随票 05 删除，
 * 「中心 → 业务」这条边塌成一次方法调用），本类直接驱动它，断言两路关单动作与幂等语义。
 *
 * <p>逐项隔离（一项抛异常不拖垮同批）的**生产所在地**是扫描 case 的批内回调
 * （{@code TimeoutTaskScanCase.callback}，见 {@code TimeoutTaskScanIntegrationTest} 的专用用例）；
 * 这里的批量驱动器与之同口径（逐项 catch、失败项进失败集），只为保住既有断言的形状。
 */
@DisplayName("票 05：进程内回调分发（两路关单 + 幂等 + 坏项隔离）")
class TimeoutTaskCallbackIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;

    @Test
    void 两路到期任务同批回调各自关单动作生效() {
        Session seller = registerAndLogin("13800001101", "卖家");
        Session buyer = registerAndLogin("13800001102", "买家");
        String productId = createApprovedProduct(seller, "九成新相机");
        String orderId = createProductOrder(buyer, productId);
        String errandId = createErrand(buyer, "帮忙取个快递");

        // 下单扣到 0 件商品已 SOLD 并落过一条搜索同步消息；关单会再落一条（回补翻回在售 → 文档重建）
        long syncBefore = searchSyncCount(productId);

        Set<String> failures = callback(timeoutTaskCallback,
                payTask(Constants.PRODUCT_ORDER_PAY_TIMEOUT, orderId),
                payTask(Constants.ERRAND_PAY_TIMEOUT, errandId));

        assertThat(failures).as("两项都应回调成功").isEmpty();

        // 商品关单 = 现 handlePayTimeout 的关单动作三者齐全：条件 UPDATE 取消 + 商品回在售 + 搜索同步消息
        assertThat(productOrderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
        assertThat(searchSyncCount(productId)).as("关单补一条搜索同步消息（商品回在售）").isEqualTo(syncBefore + 1);

        assertThat(errandStatus(errandId)).isEqualTo("CANCELLED");
    }

    @Test
    void 重复回调同批任务幂等吞掉() {
        Session seller = registerAndLogin("13800001103", "卖家2");
        Session buyer = registerAndLogin("13800001104", "买家2");
        String productId = createApprovedProduct(seller, "九成新键盘");
        String orderId = createProductOrder(buyer, productId);
        TimeoutTaskEntity taskEntity = payTask(Constants.PRODUCT_ORDER_PAY_TIMEOUT, orderId);

        callback(timeoutTaskCallback, taskEntity);
        long syncAfterFirst = searchSyncCount(productId);

        // 中心 at-least-once：同键重投（如回调成功但状态未落，进程崩溃后下轮重扫）
        Set<String> failures = callback(timeoutTaskCallback, taskEntity);

        assertThat(failures).as("重复回调仍按成功（未命中状态机 = 幂等成功）").isEmpty();
        assertThat(productOrderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(productStatus(productId)).as("商品没有被二次 release 影响").isEqualTo("ON_SALE");
        assertThat(searchSyncCount(productId)).as("重复回调零新增副作用").isEqualTo(syncAfterFirst);
    }

    @Test
    void 已支付与已取消的订单收到回调零副作用() {
        Session seller = registerAndLogin("13800001105", "卖家3");
        Session buyer = registerAndLogin("13800001106", "买家3");
        String productId = createApprovedProduct(seller, "九成新耳机");
        String paidOrderId = createProductOrder(buyer, productId);
        assertSuccess(postJson("/api/trade/orders/" + paidOrderId + "/pay", null, buyer), "支付订单");
        String errandId = createErrand(buyer, "帮忙买个饭");
        assertSuccess(postJson("/api/errand/orders/" + errandId + "/cancel", null, buyer), "取消跑腿单");

        long syncBefore = searchSyncCount(productId);

        Set<String> failures = callback(timeoutTaskCallback,
                payTask(Constants.PRODUCT_ORDER_PAY_TIMEOUT, paidOrderId),
                payTask(Constants.ERRAND_PAY_TIMEOUT, errandId));

        assertThat(failures).as("未命中状态机 = 幂等成功，不算失败").isEmpty();
        assertThat(productOrderStatus(paidOrderId)).as("已支付订单不被超时回调改成取消").isEqualTo("PAID");
        assertThat(productStatus(productId)).as("商品状态不被回调改动（单件下单已售罄即 SOLD）").isEqualTo("SOLD");
        assertThat(searchSyncCount(productId)).as("零副作用：不落搜索同步消息").isEqualTo(syncBefore);
        assertThat(errandStatus(errandId)).as("已取消订单保持取消").isEqualTo("CANCELLED");
    }

    @Test
    void 未接入的taskType与坏bizId按失败重投且不拖垮同批其他项() {
        Session seller = registerAndLogin("13800001107", "卖家4");
        Session buyer = registerAndLogin("13800001108", "买家4");
        String productId = createApprovedProduct(seller, "九成新台灯");
        String orderId = createProductOrder(buyer, productId);

        // 坏 bizId 取复合键形状错误（锁单超时 bizId 应为 {orderId}:{lockSeq}）：支付超时分支已直传
        // UUID 字符串不再解析，任何字符串都会走幂等成功，形状校验只复合键还有
        Set<String> failures = callback(timeoutTaskCallback,
                TimeoutCallbackFixture.dueTask("NO_SUCH_TASK_TYPE", orderId, 15),
                TimeoutCallbackFixture.dueTask(Constants.ERRAND_LOCK_TIMEOUT, "not-composite", 15),
                payTask(Constants.PRODUCT_ORDER_PAY_TIMEOUT, orderId));

        assertThat(failures).as("单项失败按失败重投（16 次后进死信暴露）")
                .containsExactlyInAnyOrder("NO_SUCH_TASK_TYPE:" + orderId,
                        Constants.ERRAND_LOCK_TIMEOUT + ":not-composite");
        assertThat(productOrderStatus(orderId)).as("同批的好项不受坏项影响，已关单").isEqualTo("CANCELLED");
    }

    // ---------- 回调工具 ----------

    private TimeoutTaskEntity payTask(String taskType, String bizId) {
        return TimeoutCallbackFixture.dueTask(taskType, bizId, 15);
    }

    // ---------- 用例夹具（照同目录既有用例的工具形状） ----------

    /** 发布商品（发布即 ON_SALE）；商品业务 id 是 UUID 字符串 */
    private String createApprovedProduct(Session seller, String title) {
        JsonNode publish = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(publish, "发布商品");
        return publish.path("data").asText();
    }

    private String createProductOrder(Session buyer, String productId) {
        JsonNode response = postJson("/api/trade/orders?productId=" + productId, null, buyer);
        assertSuccess(response, "商品下单");
        return response.path("data").asText();
    }

    private String createErrand(Session user, String description) {
        JsonNode response = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("title", "帮忙取个快递"),
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", description),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("deadline", LocalDateTime.now().plusHours(2).toString())), user);
        assertSuccess(response, "发布跑腿");
        return response.path("data").asText();
    }

    // ---------- 可观察状态 ----------

    private String productOrderStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product_order WHERE product_order_id = ?", String.class, orderId);
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private String errandStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }


    /** 该商品的搜索同步消息条数（下单/支付/关单各会落一条，用来断言关单补落、重复回调零新增） */
    private long searchSyncCount(String productId) {
        return jdbcTemplate.queryForList("SELECT payload FROM outbox_message WHERE destination = ?",
                        String.class, Constants.SEARCH_SYNC_TOPIC).stream()
                .map(this::readTree)
                .filter(node -> productId.equals(node.path("id").asText()) && "product".equals(node.path("type").asText()))
                .count();
    }
}
