package com.betterschool.api;

import com.betterschool.api.dto.ErrandDraftSaveRequestDTO;
import com.betterschool.api.dto.ErrandOrderPublishRequestDTO;
import com.betterschool.api.dto.ErrandOrderResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 跑腿服务契约：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface IErrandService {

    Response<String> createOrder(ErrandOrderPublishRequestDTO request);

    /** 保存草稿（草稿入口）：落 DRAFT，字段可缺省，不付款不对外、不起支付钟 */
    Response<String> saveDraft(ErrandDraftSaveRequestDTO request);

    /** 编辑草稿（仅 DRAFT 可编辑，全量覆盖内容字段） */
    Response<Void> updateDraft(String orderId, ErrandDraftSaveRequestDTO request);

    /** 提交草稿：必填 + 同步初筛通过后转待支付并注册 15 分钟支付超时 */
    Response<Void> submitDraft(String orderId);

    /** 删除草稿（软删 DELETED，仅作者、仅 DRAFT） */
    Response<Void> deleteDraft(String orderId);

    Response<Void> payOrder(String orderId);

    /**
     * 跑腿大厅（market-feed-uv 票 04）：无类型筛选且只看 PUBLISHED 时走索引序扫描 + keyset 续页
     * （cursor 非空则忽略 page），其余口径与索引查询降级时走旧直查（cursor 恒 null，按 page 续页）。
     * 索引路径 total 降口径为 -1（未知，不再精确 COUNT）
     */
    Response<PageResult<ErrandOrderResponseDTO>> listOrders(String type, String status, String sort, String cursor,
                                                            int page, int pageSize);

    Response<ErrandOrderResponseDTO> orderDetail(String orderId);

    Response<Void> grabOrder(String orderId);

    /** 确认接单（锁单→已确认，仅持单人） */
    Response<Void> acceptLockedOrder(String orderId);

    /** 放弃（锁单→已发布，仅持单人；不扣信用分） */
    Response<Void> abandonLockedOrder(String orderId);

    Response<Void> pickupOrder(String orderId);

    Response<Void> deliverOrder(String orderId);

    Response<Void> confirmOrder(String orderId);

    /**
     * 取消订单（发布方取货前 / 接单方已确认未取货）。
     * {@code requestId} 可空：客户端每次点击生成、重试沿用的请求编号（UUID），
     * 供请求级幂等判定——同编号的取消重试直接回放成功、不重复执行
     */
    Response<Void> cancelOrder(String orderId, String requestId);

    Response<Void> disputeOrder(String orderId);

    Response<Void> arbitrateOrder(String orderId, String result);

    Response<PageResult<ErrandOrderResponseDTO>> listMyOrders(String role, String status, int page, int pageSize);

    /** 作者主页「跑腿」tab（免登录）：某用户发布的进行中跑腿单，只列发布不含接单 */
    Response<PageResult<ErrandOrderResponseDTO>> listUserOrders(String userId, int page, int pageSize);
}
