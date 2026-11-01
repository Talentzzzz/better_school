package com.betterschool.marketing.integration.common;

import com.betterschool.marketing.integration.support.RaffleApiTestBase;
import com.betterschool.marketing.integration.support.VtWebThreadProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web seam 断言（spec §5.2 #1，票 12）：Web handler 必须跑在虚拟线程上。
 * 断言对象是线程模型，不看业务结果——端点任选（ping 需登录态，走基类签发的 JWT）。
 * <p>
 * 父类是 {@link RaffleApiTestBase}：它拉起的完整上下文（含 redissonClient 与全部消费者容器）
 * 才是这两个 seam 断言要看的对象；PointsApiTestBase 保留，但 VT 两个类只跑一次。
 */
class WebHandlerVirtualThreadIntegrationTest extends RaffleApiTestBase {

    @Test
    @DisplayName("Web handler 跑在虚拟线程上：防 spring.threads.virtual.enabled 被翻回")
    void webHandlerRunsOnVirtualThread() {
        VtWebThreadProbe.reset();
        get("/api/points/ping", "1");
        assertThat(VtWebThreadProbe.sawHandler()).as("探针过滤器应已执行").isTrue();
        assertThat(VtWebThreadProbe.handlerWasVirtual())
                .as("Web handler 应跑在虚拟线程上——此断言红即 spring.threads.virtual.enabled 被翻回")
                .isTrue();
    }
}
