package com.betterschool.api;

import com.betterschool.api.dto.AssignAuditorRequestDTO;
import com.betterschool.api.dto.ReportProcessRequestDTO;
import com.betterschool.api.dto.ReportResponseDTO;
import com.betterschool.api.dto.ReportSubmitRequestDTO;
import com.betterschool.api.dto.ReviewQueueItemResponseDTO;
import com.betterschool.api.dto.ReviewRequestDTO;
import com.betterschool.api.dto.ReviewTaskClaimRequestDTO;
import com.betterschool.api.dto.ReviewTaskClaimResponseDTO;
import com.betterschool.api.dto.ReviewTaskDecideRequestDTO;
import com.betterschool.api.dto.ReviewTaskDetailResponseDTO;
import com.betterschool.api.dto.ReviewTaskReleaseRequestDTO;
import com.betterschool.api.dto.SensitiveWordRequestDTO;
import com.betterschool.api.dto.SensitiveWordResponseDTO;
import com.betterschool.api.dto.WsTicketResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

import java.util.Map;

/**
 * 内容治理服务契约（moderation 域）：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface IModerationService {

    /**
     * 审核 WS 连接票据（票 10，spec §5.3/§5.4）：60s 一次性短时凭证，换取 /ws/review 握手资格
     * （浏览器 WS 不能带 header，JWT 不进 URL）。契约形状与 chat 票据接口一致——
     * 前端审核端可复用既有长连接客户端骨架。仅 AUDITOR/ADMIN；角色合规性在握手时点二次把关
     */
    Response<WsTicketResponseDTO> createWsTicket();

    /** 用户举报（帖子/评论/用户；重复举报幂等返回 null） */
    Response<Long> submitReport(ReportSubmitRequestDTO request);

    /** 审核员处理举报（核实 → 下架 + 扣分 + 通知；忽略 → 通知举报人） */
    Response<Void> processReport(Long reportId, ReportProcessRequestDTO request);

    /**
     * 审核员审核动作（通过/驳回，遗留入口，spec §5.2 收口）：按「类型 + 内容 id + 当前登录用户」反查
     * 其持有的在途工单，命中转工单裁决（强绑定），未持有 → 403「请先领单」，无绕过路径
     */
    Response<Void> review(ReviewRequestDTO request);

    /**
     * 审核队列（读 review_task 工单池：claim_state=PENDING + 类型过滤 + entered_at 先到先审；
     * targetType 缺省 = 跨类型取候选；返回条目口径与原接口兼容）
     */
    Response<PageResult<ReviewQueueItemResponseDTO>> listReviewQueue(String targetType, int page, int pageSize);

    /** 各类型积压计数（工单池 PENDING 行数，四类型缺省补 0；驱动审核端计数条与派单信号的积压数） */
    Response<Map<String, Long>> reviewTaskCounts();

    /**
     * 领单（spec §5.3 原子取一件）：一次只取一条（不预取），FIFO 先到先审（body.targetType 可选类型过滤），
     * 条件 UPDATE 抢占并发下仅一人成功；起 5 分钟租约（到点未裁决自动回收）。池空/抢占落空返回 data=null
     */
    Response<ReviewTaskClaimResponseDTO> claimTask(ReviewTaskClaimRequestDTO request);

    /** 主动放回（spec §5.2/§5.3：审不了退池 CLAIMED→PENDING，计一次回收；非持有人/已离开 CLAIMED 报错） */
    Response<Void> releaseTask(Long taskId, ReviewTaskReleaseRequestDTO request);

    /**
     * 工单裁决（spec §5.2 强绑定）：仅持有人在租约内可裁——非持有人/工单已被回收 403；
     * 业务行状态流转 + 审核记录落行 + 工单置 DONE 同一事务原子；已 DONE 的重放整体幂等成功、零副作用
     */
    Response<Void> decideTask(Long taskId, ReviewTaskDecideRequestDTO request);

    /**
     * 工单详情聚合（spec §5.1：内容/配图/作者/进入待审时刻/审核轮次通用列 + 类型专属上下文
     * + 同一内容历轮裁决 + 同一作者历史裁决；纯只读，不改变任何业务行对外可见性）
     */
    Response<ReviewTaskDetailResponseDTO> reviewTaskDetail(Long taskId);

    /** 审核对象详情（审核员看全文） */
    Response<ReviewQueueItemResponseDTO> reviewDetail(String targetType, String targetId);

    /** 举报列表（按状态过滤） */
    Response<PageResult<ReportResponseDTO>> listReports(String status, int page, int pageSize);

    /** admin 敏感词列表 */
    Response<PageResult<SensitiveWordResponseDTO>> listSensitiveWords(int page, int pageSize);

    /** admin 新增敏感词（uk 幂等） */
    Response<Void> addSensitiveWord(SensitiveWordRequestDTO request);

    /** admin 删除敏感词 */
    Response<Void> deleteSensitiveWord(Long wordId);

    /** admin 指派审核员（生效需重新登录，JWT role 是签发快照） */
    Response<Void> assignAuditor(AssignAuditorRequestDTO request);

    /** admin 回收审核员 */
    Response<Void> removeAuditor(AssignAuditorRequestDTO request);
}
