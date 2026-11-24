package com.betterschool.integration.web;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.integration.support.VtWebThreadProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web seam 断言（spec §5.2 #1，票 12）：Web handler 必须跑在虚拟线程上。
 * 断言对象是线程模型，不看业务结果——端点任选（帖子列表为免登录 GET）。
 */
class WebHandlerVirtualThreadIntegrationTest extends PostApiTestBase {

    @Test
    @DisplayName("Web handler 跑在虚拟线程上：防 spring.threads.virtual.enabled 被翻回")
    void webHandlerRunsOnVirtualThread() {
        VtWebThreadProbe.reset();
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/community/posts?page=1&pageSize=1", null, null);
        assertThat(response.getStatusCode().value()).as("帖子列表应免登录 200：%s", response.getBody()).isEqualTo(200);
        assertThat(VtWebThreadProbe.sawHandler()).as("探针过滤器应已执行").isTrue();
        assertThat(VtWebThreadProbe.handlerWasVirtual())
                .as("Web handler 应跑在虚拟线程上——此断言红即 spring.threads.virtual.enabled 被翻回")
                .isTrue();
    }
}
